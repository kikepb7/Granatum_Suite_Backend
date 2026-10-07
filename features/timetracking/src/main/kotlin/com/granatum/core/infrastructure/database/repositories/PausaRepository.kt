package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.infrastructure.database.entities.PausaEntity
import org.springframework.data.repository.Repository
import java.util.UUID

/** No mutation beyond `save`; see [EmpleadoRepository] for why. */
interface PausaRepository : Repository<PausaEntity, UUID> {
    fun save(pausa: PausaEntity): PausaEntity
    fun findByFichajeIdAndFinIsNull(fichajeId: UUID): PausaEntity?
}
