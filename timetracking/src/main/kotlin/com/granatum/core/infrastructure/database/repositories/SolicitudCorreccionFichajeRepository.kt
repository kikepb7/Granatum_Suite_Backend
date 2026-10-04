package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.domain.type.EstadoSolicitud
import com.granatum.core.infrastructure.database.entities.SolicitudCorreccionFichajeEntity
import org.springframework.data.repository.Repository
import java.util.Optional
import java.util.UUID

/** No delete; see [EmpleadoRepository] for why. */
interface SolicitudCorreccionFichajeRepository :
    Repository<SolicitudCorreccionFichajeEntity, UUID> {

    fun save(solicitud: SolicitudCorreccionFichajeEntity): SolicitudCorreccionFichajeEntity
    fun findById(id: UUID): Optional<SolicitudCorreccionFichajeEntity>
    fun findAllByFichajeIdOrderByCreatedAtDesc(fichajeId: UUID): List<SolicitudCorreccionFichajeEntity>
    fun findAllByEstado(estado: EstadoSolicitud): List<SolicitudCorreccionFichajeEntity>
    fun existsByFichajeIdAndEstado(fichajeId: UUID, estado: EstadoSolicitud): Boolean
}
