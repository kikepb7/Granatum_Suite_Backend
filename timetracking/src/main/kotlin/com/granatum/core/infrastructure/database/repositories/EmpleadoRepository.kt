package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.infrastructure.database.entities.EmpleadoEntity
import org.springframework.data.repository.Repository
import java.util.Optional
import java.util.UUID

/**
 * Deliberately extends [Repository] and not `JpaRepository`.
 *
 * FR-030 forbids deleting staff, and constitution principle III (v2.0.0)
 * requires repositories of append-only or non-deletable tables not to expose
 * mutation operations at all. Not calling `delete` is not enough: an interface
 * that offers it will eventually be used by accident, and there would be
 * nothing to stop it.
 */
interface EmpleadoRepository : Repository<EmpleadoEntity, UUID> {
    fun save(empleado: EmpleadoEntity): EmpleadoEntity
    fun findById(id: UUID): Optional<EmpleadoEntity>
    fun findAll(): List<EmpleadoEntity>
    fun findAllByActivo(activo: Boolean): List<EmpleadoEntity>
    fun findByDocumentoIdentidad(documentoIdentidad: String): EmpleadoEntity?
    fun existsByDocumentoIdentidad(documentoIdentidad: String): Boolean
}
