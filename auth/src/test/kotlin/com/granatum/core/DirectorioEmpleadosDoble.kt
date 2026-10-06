package com.granatum.core

import com.granatum.core.domain.contract.DirectorioEmpleados
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.EstadoEmpleado
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * The `auth` module's stand-in for `DirectorioEmpleados`.
 *
 * `timetracking` owns the real implementation and this module does not depend on
 * it - that is the whole point of the contract (principle I). So a double is not
 * a shortcut here: it is the only thing that *can* exist on this classpath, and
 * the real wiring is exercised in `app`, which has both modules.
 *
 * Mutable on purpose: a test needs to dismiss somebody between two calls to
 * check that renewal is refused while an already-issued access token is not
 * (FR-010).
 */
class DirectorioEmpleadosDoble : DirectorioEmpleados {

    private val estados = mutableMapOf<EntityId, EstadoEmpleado>()

    fun registrarActivo(id: EntityId) { estados[id] = EstadoEmpleado.ACTIVO }

    fun registrarInactivo(id: EntityId) { estados[id] = EstadoEmpleado.INACTIVO }

    fun olvidar(id: EntityId) { estados.remove(id) }

    override fun estado(empleadoId: EntityId): EstadoEmpleado? = estados[empleadoId]

    override fun existentes(empleadoIds: Collection<EntityId>): Set<EntityId> =
        empleadoIds.filter { estados.containsKey(it) }.toSet()
}

@TestConfiguration
class DirectorioEmpleadosDobleConfig {
    @Bean
    fun directorioEmpleados() = DirectorioEmpleadosDoble()
}
