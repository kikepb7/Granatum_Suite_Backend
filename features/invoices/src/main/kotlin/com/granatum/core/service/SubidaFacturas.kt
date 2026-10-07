package com.granatum.core.service

import com.granatum.core.domain.exception.EstadoFacturaNoPermitidoException
import com.granatum.core.domain.exception.FacturaNoEncontradaException
import com.granatum.core.domain.exception.ReconocimientoNoDisponibleException
import com.granatum.core.domain.model.EstadoFactura
import com.granatum.core.domain.port.AlmacenDocumentos
import com.granatum.core.domain.port.ReconocedorFacturas
import com.granatum.core.infrastructure.database.entities.FacturaEntity
import com.granatum.core.infrastructure.database.repositories.FacturaRepository
import com.granatum.core.infrastructure.documentos.DetectorTipoFichero
import com.granatum.core.infrastructure.documentos.DocumentoNoAdmitido
import com.granatum.core.infrastructure.documentos.PreparadorDocumento
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Clock
import java.util.HexFormat
import java.util.UUID

/** One uploaded file: its bytes and, for reference only, its name. */
class FicheroSubido(val nombre: String?, val contenido: ByteArray) {
    override fun toString(): String = "FicheroSubido(bytes=${contenido.size})"
}

/**
 * The outcome for one file, identified by its **position** and never by its
 * name, which may be personal and must not travel back or reach a log.
 */
data class ResultadoSubida(val fichero: Int, val resultado: String, val facturaId: UUID?)

/**
 * Stores uploaded invoices and hands them to the recognition queue
 * (FR-001, FR-002, FR-014; research.md D-005, D-007, D-015).
 *
 * Each file in **its own** transaction: one refused or duplicated file does not
 * undo the others. The recognition is queued only **after** the commit, and
 * runs outside any transaction (see [ColaReconocimiento]).
 */
@Service
class SubidaFacturas(
    private val facturas: FacturaRepository,
    private val almacen: AlmacenDocumentos,
    private val preparador: PreparadorDocumento,
    private val cola: ColaReconocimiento,
    private val reconocedor: ReconocedorFacturas,
    transactionManager: PlatformTransactionManager,
    @param:Value("\${invoices.documentos.max-bytes-por-fichero:10485760}") private val maxBytesPorFichero: Long,
    private val clock: Clock = Clock.systemUTC()
) {
    private val transaccion = TransactionTemplate(transactionManager)

    fun subir(ficheros: List<FicheroSubido>, autor: UUID): List<ResultadoSubida> =
        ficheros.mapIndexed { i, fichero -> subirUno(i + 1, fichero, autor) }

    private fun subirUno(posicion: Int, fichero: FicheroSubido, autor: UUID): ResultadoSubida {
        val contenido = fichero.contenido
        if (contenido.isEmpty()) return ResultadoSubida(posicion, "VACIO", null)
        // The per-file limit lives here and not in Spring's multipart settings,
        // so one big file does not fail the whole upload (finding I1).
        if (contenido.size > maxBytesPorFichero) return ResultadoSubida(posicion, "DEMASIADO_GRANDE", null)
        val tipo = DetectorTipoFichero.detectar(contenido) ?: return ResultadoSubida(posicion, "FORMATO_NO_ADMITIDO", null)
        try {
            preparador.examinar(contenido, tipo)
        } catch (e: DocumentoNoAdmitido) {
            return ResultadoSubida(posicion, e.resultado, null)
        }

        val huella = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenido))
        duplicada(huella)?.let { return ResultadoSubida(posicion, "DUPLICADA", it) }

        val id = try {
            transaccion.execute {
                val factura = facturas.saveAndFlush(
                    FacturaEntity(documentoSha256 = huella, subidaPor = autor, subidaEn = clock.instant())
                )
                almacen.guardar(factura.id, contenido, tipo.mediaType, fichero.nombre)
                factura.id
            }!!
        } catch (e: DataIntegrityViolationException) {
            // Another upload of the same file won the race: the unique index
            // decided, and this one is its duplicate (D-015).
            return ResultadoSubida(posicion, "DUPLICADA", duplicada(huella))
        }

        cola.encolar(id)
        return ResultadoSubida(posicion, "ACEPTADA", id)
    }

    private fun duplicada(huella: String): UUID? =
        transaccion.execute { facturas.findFirstByDocumentoSha256AndEstadoNot(huella, EstadoFactura.DESCARTADA)?.id }

    /** `POST …/reconocer`: queue it again (FR-006). */
    fun reintentar(id: UUID) {
        if (!reconocedor.activo) throw ReconocimientoNoDisponibleException()
        val estado = transaccion.execute {
            facturas.findById(id).map { it.estado }.orElseThrow { FacturaNoEncontradaException(id) }
        }!!
        if (estado != EstadoFactura.PENDIENTE_RECONOCER && estado != EstadoFactura.BORRADOR) {
            throw EstadoFacturaNoPermitidoException("Solo se reconoce una factura pendiente o en borrador")
        }
        cola.encolar(id)
    }
}
