package com.granatum.core.infrastructure.directorio

import com.granatum.core.domain.contract.DirectorioEmpleados
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.EstadoEmpleado
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * This module's side of the [DirectorioEmpleados] contract.
 *
 * `timetracking` owns the `empleados` table, so it is the only module that can
 * answer. `auth` consumes the interface from `common` and never sees this class
 * - which is the whole point of principle I: the dependency points inward, to
 * the shared contract, and not sideways between features.
 *
 * It lives in `infrastructure/` rather than `service/` because it is an adapter:
 * it translates a persistence detail (`activo` as a boolean column) into the
 * vocabulary another feature asked for, and holds no business rule of its own.
 */
@Component
class DirectorioEmpleadosJpa(
    private val empleadoRepository: EmpleadoRepository
) : DirectorioEmpleados {

    @Transactional(readOnly = true)
    override fun estado(empleadoId: EntityId): EstadoEmpleado? =
        empleadoRepository.findById(empleadoId)
            .map { if (it.activo) EstadoEmpleado.ACTIVO else EstadoEmpleado.INACTIVO }
            .orElse(null)

    /**
     * One query, as the contract requires. An empty input short-circuits rather
     * than issuing `IN ()`, which Postgres rejects outright.
     */
    @Transactional(readOnly = true)
    override fun existentes(empleadoIds: Collection<EntityId>): Set<EntityId> {
        if (empleadoIds.isEmpty()) return emptySet()
        return empleadoRepository.findExistingIds(empleadoIds).toSet()
    }
}
