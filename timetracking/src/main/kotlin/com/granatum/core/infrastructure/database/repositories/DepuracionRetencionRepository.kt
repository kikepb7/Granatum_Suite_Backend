package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.infrastructure.database.entities.DepuracionRetencionEntity
import org.springframework.data.repository.Repository
import java.util.UUID

/**
 * The purge audit log. Append-only and never itself purged, so it exposes an
 * insert and a read and nothing else.
 */
interface DepuracionRetencionRepository : Repository<DepuracionRetencionEntity, UUID> {
    fun save(depuracion: DepuracionRetencionEntity): DepuracionRetencionEntity
    fun findAllByOrderByEjecutadaEnDesc(): List<DepuracionRetencionEntity>
}
