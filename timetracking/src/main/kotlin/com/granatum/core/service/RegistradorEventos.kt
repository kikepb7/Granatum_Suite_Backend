package com.granatum.core.service

import com.granatum.core.domain.type.TipoOperacionFichaje
import com.granatum.core.infrastructure.database.entities.FichajeEventoEntity
import com.granatum.core.infrastructure.database.repositories.FichajeEventoRepository
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Writes one row to the append-only event log per received operation.
 *
 * Required by constitution principle III (v1.1.0) for **every** fichaje
 * operation, not only for the idempotency of the offline story: this log is the
 * evidentiary record of the working-time register. What matters to a labour
 * inspection is what the person clocked and when, not the aggregated state.
 *
 * The projection tables (`fichajes`, `pausas`) must at all times be derivable
 * from this log plus approved corrections. If a value in the projection is not
 * explained by an event or a correction, it was written through a path that
 * should not exist - which is what `DerivabilidadEstadoIT` asserts.
 */
@Service
class RegistradorEventos(
    private val fichajeEventoRepository: FichajeEventoRepository,
    private val clock: Clock = Clock.systemUTC()
) {

    /**
     * Records [tipoOperacion] and returns the stored event.
     *
     * [occurredAt] is when it actually happened per the device and is what the
     * working-time computation uses; `receivedAt` is taken here and is never
     * used for computation (FR-025).
     */
    fun registrar(
        clientEventId: UUID,
        empleadoId: UUID,
        fichajeId: UUID?,
        tipoOperacion: TipoOperacionFichaje,
        occurredAt: Instant,
        cuerpoPeticion: String,
        estadoRespuesta: Int,
        cuerpoRespuesta: String
    ): FichajeEventoEntity =
        fichajeEventoRepository.save(
            FichajeEventoEntity(
                clientEventId = clientEventId,
                empleadoId = empleadoId,
                fichajeId = fichajeId,
                tipoOperacion = tipoOperacion,
                occurredAt = occurredAt,
                receivedAt = clock.instant(),
                huellaPeticion = huella(cuerpoPeticion),
                estadoRespuesta = estadoRespuesta,
                cuerpoRespuesta = cuerpoRespuesta
            )
        )

    fun buscarPorClientEventId(clientEventId: UUID): FichajeEventoEntity? =
        fichajeEventoRepository.findByClientEventId(clientEventId)

    /**
     * SHA-256 of the request body. Stored so that the same idempotency key
     * arriving with a *different* body can be rejected as a conflict instead of
     * silently returning another operation's response - which is what would
     * happen if only the key were compared, and the client would never find out.
     */
    fun huella(cuerpo: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(cuerpo.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
