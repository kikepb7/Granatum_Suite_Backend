package com.granatum.core.service

import com.fasterxml.jackson.module.kotlin.readValue
import com.granatum.core.domain.exception.EmpresaSinConfigurarException
import com.granatum.core.domain.exception.EstadoFacturaNoPermitidoException
import com.granatum.core.domain.exception.FacturaDuplicadaException
import com.granatum.core.domain.exception.FacturaIncoherenteException
import com.granatum.core.domain.exception.FacturaNoEncontradaException
import com.granatum.core.domain.exception.VersionFacturaDesactualizadaException
import com.granatum.core.domain.model.Aviso
import com.granatum.core.domain.model.CodigoAviso
import com.granatum.core.domain.model.EstadoFactura
import com.granatum.core.domain.model.Factura
import com.granatum.core.domain.service.ClasificadorFactura
import com.granatum.core.domain.service.ContextoValidacion
import com.granatum.core.domain.service.ValidadorFactura
import com.granatum.core.infrastructure.database.JsonFacturacion
import com.granatum.core.infrastructure.database.aDominio
import com.granatum.core.infrastructure.database.entities.FacturaCambioEntity
import com.granatum.core.infrastructure.database.entities.FacturaEntity
import com.granatum.core.infrastructure.database.entities.FacturaLineaIvaEntity
import com.granatum.core.infrastructure.database.repositories.FacturaCambioRepository
import com.granatum.core.infrastructure.database.repositories.FacturaReconocimientoRepository
import com.granatum.core.infrastructure.database.repositories.FacturaRepository
import com.granatum.core.validation.NifValidator
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Reviewing invoices: correcting, confirming, discarding (FR-009 to FR-017).
 *
 * - Nothing is confirmed with a blocking warning ([ValidadorFactura]).
 * - Every change to an invoice of a quarter, and its confirmation, first locks
 *   the quarter's row and refuses if it is closed (research.md D-016, FR-031).
 * - A confirmed invoice is changed only with its previous values recorded
 *   (FR-016), and never left incomplete.
 * - The logical duplicate is checked here for a readable error and refused by
 *   the partial unique index whatever happens here (D-015).
 */
@Service
class RevisionFacturas(
    private val facturas: FacturaRepository,
    private val cambios: FacturaCambioRepository,
    private val reconocimientos: FacturaReconocimientoRepository,
    private val empresa: EmpresaService,
    private val trimestres: BloqueoTrimestres,
    private val sonda: SondaConcurrencia,
    private val json: JsonFacturacion,
    private val clock: Clock = Clock.systemUTC()
) {
    private val madrid = ZoneId.of("Europe/Madrid")

    @Transactional
    fun corregir(id: UUID, valores: ValoresFactura, version: Int, autor: UUID): Factura {
        val f = cargar(id, version)
        if (f.estado == EstadoFactura.DESCARTADA) throw EstadoFacturaNoPermitidoException("Una factura descartada no se corrige")

        if (f.estado == EstadoFactura.CONFIRMADA) {
            val antes = f.aDominio()
            f.fechaEmision?.let(trimestres::exigirAbierto)
            valores.fechaEmision?.takeIf { it != f.fechaEmision }?.let(trimestres::exigirAbierto)
            val nueva = aplicar(f, valores).aDominio()
            val bloqueantes = ValidadorFactura.bloqueantes(avisos(nueva, conDuplicado = true))
            if (bloqueantes.isNotEmpty()) throw FacturaIncoherenteException(resumen(bloqueantes))
            val soloTipo = antes.copy(tipo = nueva.tipo, version = 0) == nueva.copy(version = 0)
            sonda.trasComprobar(id)
            registrarCambio(id, if (soloTipo) FacturaCambioEntity.RECLASIFICACION else FacturaCambioEntity.CORRECCION, antes, autor)
        } else {
            aplicar(f, valores)
            // FR-006: filling a pending invoice in by hand makes it a draft.
            f.estado = EstadoFactura.BORRADOR
        }
        return guardar(f)
    }

    @Transactional
    fun confirmar(id: UUID, version: Int, autor: UUID): Factura {
        val f = cargar(id, version)
        if (f.estado != EstadoFactura.BORRADOR) throw EstadoFacturaNoPermitidoException("Solo se confirma un borrador")
        val datos = empresa.buscar() ?: throw EmpresaSinConfigurarException()
        if (f.tipo == null) f.tipo = ClasificadorFactura.clasificar(datos.nifNormalizado, f.emisorNif, f.destinatarioNif)

        f.fechaEmision?.let(trimestres::exigirAbierto)
        val factura = f.aDominio()
        val todos = avisos(factura, conDuplicado = true)
        if (todos.any { it.codigo == CodigoAviso.DUPLICADA }) throw FacturaDuplicadaException()
        val bloqueantes = ValidadorFactura.bloqueantes(todos)
        if (bloqueantes.isNotEmpty()) throw FacturaIncoherenteException(resumen(bloqueantes))

        sonda.trasComprobar(id)

        f.estado = EstadoFactura.CONFIRMADA
        f.confirmadaPor = autor
        f.confirmadaEn = clock.instant()
        return guardar(f)
    }

    @Transactional
    fun descartar(id: UUID, version: Int, autor: UUID): Factura {
        val f = cargar(id, version)
        when (f.estado) {
            EstadoFactura.DESCARTADA -> throw EstadoFacturaNoPermitidoException("La factura ya esta descartada")
            EstadoFactura.CONFIRMADA -> {
                f.fechaEmision?.let(trimestres::exigirAbierto)
                sonda.trasComprobar(id)
                registrarCambio(id, FacturaCambioEntity.DESCARTE, f.aDominio(), autor)
            }
            else -> Unit
        }
        f.estado = EstadoFactura.DESCARTADA
        f.descartadaPor = autor
        f.descartadaEn = clock.instant()
        return guardar(f)
    }

    /** The warnings shown with an invoice (`GET …/{id}`). */
    @Transactional(readOnly = true)
    fun avisosDe(id: UUID): List<Aviso> {
        val f = facturas.findById(id).orElseThrow { FacturaNoEncontradaException(id) }
        if (f.estado == EstadoFactura.DESCARTADA) return emptyList()
        return avisos(f.aDominio(), conDuplicado = f.estado != EstadoFactura.CONFIRMADA)
    }

    private fun avisos(f: Factura, conDuplicado: Boolean): List<Aviso> {
        val datos = empresa.buscar()
        val ultimo = reconocimientos.findFirstByFacturaIdOrderByCreadoEnDesc(f.id)
        val clasificable = datos != null && (f.emisor?.nif != null || f.destinatario?.nif != null)
        val ctx = ContextoValidacion(
            hoy = LocalDate.now(clock.withZone(madrid)),
            camposDudosos = if (f.estado == EstadoFactura.CONFIRMADA) emptyList()
                else ultimo?.camposDudosos?.let { json.mapper.readValue<List<String>>(it) } ?: emptyList(),
            duplicada = conDuplicado && duplicada(f),
            trimestreCerrado = f.fechaEmision?.let(trimestres::estaCerrado) ?: false,
            resultadoReconocimiento = ultimo?.resultado,
            noEsDeLaEmpresa = clasificable &&
                ClasificadorFactura.clasificar(datos!!.nifNormalizado, f.emisor?.nif, f.destinatario?.nif) == null
        )
        return ValidadorFactura.validar(f, ctx)
    }

    private fun duplicada(f: Factura): Boolean {
        val nif = f.emisor?.nif?.let(NifValidator::normalizarFiscal) ?: return false
        val numero = f.numero ?: return false
        val fecha = f.fechaEmision ?: return false
        return facturas.existeConfirmadaIgual(nif, numero, fecha, f.id)
    }

    private fun cargar(id: UUID, version: Int): FacturaEntity {
        val f = facturas.findById(id).orElseThrow { FacturaNoEncontradaException(id) }
        if (f.version != version) throw VersionFacturaDesactualizadaException()
        return f
    }

    private fun aplicar(f: FacturaEntity, v: ValoresFactura): FacturaEntity {
        f.tipo = v.tipo
        f.emisorNombre = v.emisorNombre
        f.emisorNif = v.emisorNif
        f.emisorNifNormalizado = v.emisorNif?.let(NifValidator::normalizarFiscal)
        f.destinatarioNombre = v.destinatarioNombre
        f.destinatarioNif = v.destinatarioNif
        f.numero = v.numero
        f.fechaEmision = v.fechaEmision
        f.concepto = v.concepto
        f.moneda = v.moneda
        f.retenciones = v.retenciones
        f.total = v.total
        f.rectificativa = v.rectificativa
        f.reemplazarLineas(v.lineas.map { FacturaLineaIvaEntity(tipoIva = it.tipoIva, base = it.base, cuota = it.cuota, recargo = it.recargo, causaSinCuota = it.causaSinCuota) })
        return f
    }

    private fun registrarCambio(id: UUID, accion: String, antes: Factura, autor: UUID) {
        cambios.save(FacturaCambioEntity(facturaId = id, accion = accion, valoresAnteriores = json.escribir(antes), autorId = autor, ocurridoEn = clock.instant()))
    }

    private fun guardar(f: FacturaEntity): Factura = try {
        facturas.saveAndFlush(f).aDominio()
    } catch (e: DataIntegrityViolationException) {
        // Only the partial unique index means "duplicate" (D-015). Any other
        // violation is a real error and must surface as one: translating them
        // all once disguised a CHECK failure as a duplicate.
        if (generateSequence(e as Throwable) { it.cause }.any { it.message?.contains("uk_facturas_confirmada") == true }) {
            throw FacturaDuplicadaException()
        }
        throw e
    } catch (e: ObjectOptimisticLockingFailureException) {
        throw VersionFacturaDesactualizadaException()
    }

    private fun resumen(bloqueantes: List<Aviso>) =
        bloqueantes.joinToString("; ") { "${it.campo}: ${it.codigo}" }
}
