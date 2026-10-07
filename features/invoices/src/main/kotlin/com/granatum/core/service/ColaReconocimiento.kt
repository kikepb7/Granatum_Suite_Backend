package com.granatum.core.service

import com.granatum.core.domain.model.CausaSinCuota
import com.granatum.core.domain.model.EstadoFactura
import com.granatum.core.domain.model.PropuestaReconocida
import com.granatum.core.domain.port.AlmacenDocumentos
import com.granatum.core.domain.port.ReconocedorFacturas
import com.granatum.core.domain.port.ResultadoReconocimiento
import com.granatum.core.infrastructure.database.JsonFacturacion
import com.granatum.core.infrastructure.database.entities.FacturaEntity
import com.granatum.core.infrastructure.database.entities.FacturaLineaIvaEntity
import com.granatum.core.infrastructure.database.entities.FacturaReconocimientoEntity
import com.granatum.core.infrastructure.database.repositories.FacturaReconocimientoRepository
import com.granatum.core.infrastructure.database.repositories.FacturaRepository
import com.granatum.core.infrastructure.documentos.PreparadorDocumento
import com.granatum.core.infrastructure.documentos.TipoDocumento
import com.granatum.core.validation.NifValidator
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Recognises invoices in the background, a few at a time (research.md D-005).
 *
 * ## Never with a transaction open while Claude answers
 *
 * A call can take tens of seconds. Holding a database connection meanwhile is
 * what exhausted the pool in feature 002 and held one for a stalled client in
 * feature 003. So each invoice goes through three separate steps: a short
 * read-only transaction to fetch the original, the call **with no transaction
 * at all**, and a short new transaction to write the result.
 * `SubidaFacturasIT` checks that the recogniser never sees one open.
 *
 * At most `concurrencia` recognitions at a time, to stay within the API's rate
 * limits; an invoice already in the queue is not queued twice.
 */
@Component
class ColaReconocimiento(
    private val reconocedor: ReconocedorFacturas,
    private val facturas: FacturaRepository,
    private val reconocimientos: FacturaReconocimientoRepository,
    private val almacen: AlmacenDocumentos,
    private val preparador: PreparadorDocumento,
    private val json: JsonFacturacion,
    transactionManager: PlatformTransactionManager,
    @param:Value("\${invoices.reconocimiento.concurrencia:3}") concurrencia: Int,
    private val clock: Clock = Clock.systemUTC()
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transacciones = TransactionTemplate(transactionManager)
    private val enCurso: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val numero = AtomicInteger()
    private val ejecutor = Executors.newFixedThreadPool(concurrencia) { tarea ->
        Thread(tarea, "reconocimiento-facturas-${numero.incrementAndGet()}").apply { isDaemon = true }
    }

    /** Queues [id] unless recognition is off or it is already queued. Never call it inside a transaction. */
    fun encolar(id: UUID): Boolean {
        if (!reconocedor.activo || !enCurso.add(id)) return false
        ejecutor.submit {
            try {
                procesar(id)
            } catch (e: Exception) {
                // The kind only: an exception message could quote the document.
                log.warn("Reconocimiento de la factura {} fallido: {}", id, e.javaClass.simpleName)
            } finally {
                enCurso.remove(id)
            }
        }
        return true
    }

    private fun procesar(id: UUID) {
        // 1. Short read: the original, if the invoice can still be recognised.
        val original = transacciones.execute {
            val f = facturas.findById(id).orElse(null)
            if (f == null || f.estado !in RECONOCIBLES) null else almacen.leer(id)
        } ?: return

        // 2. The call, with NO transaction (D-005).
        val enviar = preparador.paraEnviar(original.contenido, TipoDocumento.deMediaType(original.mediaType))
        val resultado = try {
            reconocedor.reconocer(enviar)
        } catch (e: Exception) {
            ResultadoReconocimiento.Fallida("ERROR_INTERNO", "desconocido")
        }

        // 3. Short write: the attempt, and the proposal into the draft.
        transacciones.executeWithoutResult { guardar(id, resultado) }
    }

    private fun guardar(id: UUID, resultado: ResultadoReconocimiento) {
        val factura = facturas.findById(id).orElse(null) ?: return
        val (codigo, propuesta, error) = when (resultado) {
            is ResultadoReconocimiento.Reconocida -> Triple(
                when {
                    !resultado.propuesta.esFactura -> "NO_ES_FACTURA"
                    resultado.propuesta.variasFacturas -> "VARIAS_FACTURAS"
                    else -> "RECONOCIDA"
                },
                resultado.propuesta, null
            )
            is ResultadoReconocimiento.Rechazada -> Triple("RECHAZADA", null, resultado.categoria?.let { "REFUSAL:$it" } ?: "REFUSAL")
            is ResultadoReconocimiento.Fallida -> Triple("ERROR", null, resultado.tipo)
        }
        reconocimientos.save(
            FacturaReconocimientoEntity(
                facturaId = id,
                resultado = codigo,
                modelo = resultado.modelo,
                propuesta = propuesta?.let(json::escribir),
                camposDudosos = propuesta?.let { json.escribir(it.camposDudosos) },
                tokensEntrada = resultado.tokensEntrada,
                tokensSalida = resultado.tokensSalida,
                error = error?.take(200),
                creadoEn = clock.instant()
            )
        )
        // Only an invoice still waiting or in draft takes the proposal: a
        // confirmed or discarded one is the reviewer's, not the model's.
        if (propuesta != null && factura.estado in RECONOCIBLES) aplicar(factura, propuesta)
    }

    /**
     * Fills the draft with what was proposed. What does not parse stays empty
     * rather than being guessed (FR-004); a non-invoice gets no figures.
     */
    private fun aplicar(f: FacturaEntity, p: PropuestaReconocida) {
        f.estado = EstadoFactura.BORRADOR
        if (!p.esFactura) {
            f.reemplazarLineas(emptyList())
            f.total = null
            return
        }
        f.emisorNombre = p.emisorNombre?.take(200)
        f.emisorNif = p.emisorNif?.take(20)
        f.emisorNifNormalizado = p.emisorNif?.let { NifValidator.normalizarFiscal(it).take(20) }
        f.destinatarioNombre = p.destinatarioNombre?.take(200)
        f.destinatarioNif = p.destinatarioNif?.take(20)
        f.numero = p.numero?.take(60)
        f.fechaEmision = p.fechaEmision?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        f.concepto = p.concepto?.take(500)
        f.moneda = p.moneda?.uppercase()?.takeIf { it.length == 3 } ?: "EUR"
        f.retenciones = importe(p.retenciones) ?: BigDecimal.ZERO.setScale(2)
        f.total = importe(p.total)
        f.rectificativa = p.rectificativa ?: false
        f.reemplazarLineas(p.lineas.mapNotNull { l ->
            val tipo = importe(l.tipoIva) ?: return@mapNotNull null
            val base = importe(l.base) ?: return@mapNotNull null
            val cuota = importe(l.cuota) ?: return@mapNotNull null
            FacturaLineaIvaEntity(
                tipoIva = tipo,
                base = base,
                cuota = cuota,
                recargo = importe(l.recargo) ?: BigDecimal.ZERO.setScale(2),
                causaSinCuota = l.causaSinCuota?.let { c -> runCatching { CausaSinCuota.valueOf(c) }.getOrNull() }
            )
        })
    }

    private fun importe(texto: String?): BigDecimal? =
        texto?.trim()?.let { runCatching { BigDecimal(it).setScale(2, RoundingMode.HALF_UP) }.getOrNull() }

    @PreDestroy
    fun parar() {
        ejecutor.shutdownNow()
    }

    companion object {
        private val RECONOCIBLES = setOf(EstadoFactura.PENDIENTE_RECONOCER, EstadoFactura.BORRADOR)
    }
}
