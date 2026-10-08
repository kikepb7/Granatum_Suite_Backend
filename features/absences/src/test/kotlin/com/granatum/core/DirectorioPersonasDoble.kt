package com.granatum.core

import com.granatum.core.domain.contract.DirectorioEmpleados
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.EstadoEmpleado
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.util.UUID

/**
 * This module's stand-in for `DirectorioEmpleados` (feature 007). In
 * production `timetracking` implements it; here the staff register is a map,
 * because this module must not depend on `timetracking` even in tests.
 */
class DirectorioPersonasDoble : DirectorioEmpleados {

    private val estados = mutableMapOf<EntityId, EstadoEmpleado>()

    fun activa(id: EntityId = UUID.randomUUID()): EntityId = id.also { estados[it] = EstadoEmpleado.ACTIVO }

    fun inactiva(id: EntityId = UUID.randomUUID()): EntityId = id.also { estados[it] = EstadoEmpleado.INACTIVO }

    override fun estado(empleadoId: EntityId): EstadoEmpleado? = estados[empleadoId]

    override fun existentes(empleadoIds: Collection<EntityId>): Set<EntityId> =
        empleadoIds.filter { estados.containsKey(it) }.toSet()
}

@TestConfiguration
class DirectorioPersonasDobleConfig {
    @Bean
    fun directorioEmpleados() = DirectorioPersonasDoble()
}
