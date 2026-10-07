package com.granatum.core.service

import com.granatum.core.domain.model.AlcanceExportacion
import com.granatum.core.domain.model.RangoEfectivo
import com.granatum.core.domain.type.AlcanceRegistro
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.entities.ExportacionEntity
import com.granatum.core.infrastructure.database.repositories.ExportacionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import com.granatum.core.api.util.RangoFechas
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Writes the audit row of one export, once, complete or cut short (D-003).
 *
 * **NEVER CALLED WITH THE READ TRANSACTION STILL OPEN.** An export holds one
 * connection for its whole read; writing here inside it would take a second one
 * while the first is held. In feature 002 exactly that - a write in its own
 * transaction while another stayed open - exhausted the pool under load and
 * froze every request. So the export reads, closes its transaction, and only
 * then calls this.
 *
 * `Propagation.NEVER` makes that rule enforced rather than documented: a call
 * that arrives inside a transaction fails at once instead of quietly taking a
 * second connection. The insert then runs in the repository's own transaction.
 */
@Service
class RegistroExportaciones(
    private val exportaciones: ExportacionRepository
) {
    @Transactional(propagation = Propagation.NEVER)
    fun registrar(
        solicitanteId: UUID,
        rol: Role,
        alcance: AlcanceExportacion,
        rango: RangoEfectivo,
        generadaEn: Instant,
        completada: Boolean,
        filas: Int,
        huella: String?
    ): ExportacionEntity {
        val (tipo, empleadoId) = when (alcance) {
            is AlcanceExportacion.Persona -> AlcanceRegistro.PERSONA to alcance.empleadoId
            is AlcanceExportacion.Mensual -> AlcanceRegistro.MENSUAL to alcance.empleadoId
            AlcanceExportacion.Plantilla -> AlcanceRegistro.PLANTILLA to null
        }
        return exportaciones.save(
            ExportacionEntity(
                solicitanteId = solicitanteId,
                rolSolicitante = rol,
                alcance = tipo,
                empleadoId = empleadoId,
                desde = rango.desde,
                hasta = rango.hasta,
                generadaEn = generadaEn,
                completada = completada,
                filas = filas,
                // A cut-short file was never received whole, so there is nothing
                // to recognise later; the table enforces the same rule.
                huella = if (completada) huella else null
            )
        )
    }

    /**
     * The log, filtered as FR-029 asks (see [ExportacionRepository.buscar]).
     *
     * Generation dates are whole civil days in Madrid, both ends inclusive: an
     * export made at 00:30 on May 8th belongs to May 8th, although in UTC it is
     * still May 7th.
     */
    @Transactional(readOnly = true)
    fun consultar(
        empleadoId: UUID?,
        generadaDesde: LocalDate?,
        generadaHasta: LocalDate?,
        cubreDesde: LocalDate?,
        cubreHasta: LocalDate?
    ): List<ExportacionEntity> =
        exportaciones.buscar(
            empleadoId,
            generadaDesde?.let(RangoFechas::inicioDelDia),
            generadaHasta?.let(RangoFechas::finDelDiaExclusivo),
            cubreDesde,
            cubreHasta
        )
}
