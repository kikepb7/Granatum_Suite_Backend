package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.infrastructure.database.entities.EmpleadoEntity
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
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

    /**
     * Only the ids, and only those that exist. Serves
     * [com.granatum.core.domain.contract.DirectorioEmpleados.existentes], whose
     * contract requires a single query: the orphan-account sweep walks every
     * account, so a loop would be a guaranteed N+1.
     *
     * Returns the ids rather than the entities on purpose. The caller needs to
     * know which exist, not who they are, and loading full staff records -
     * names and identity documents included - to answer that would be handing
     * personal data to a caller that has no use for it (principle VI).
     */
    @Query("SELECT e.id FROM EmpleadoEntity e WHERE e.id IN :ids")
    fun findExistingIds(@Param("ids") ids: Collection<UUID>): List<UUID>
}
