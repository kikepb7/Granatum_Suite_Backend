package com.granatum.core

import com.granatum.core.domain.contract.AltaFichaPersonal
import com.granatum.core.domain.contract.DirectorioEmpleados
import com.granatum.core.domain.contract.FichasPersonal
import com.granatum.core.validation.NifValidator
import java.util.UUID
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
class DirectorioEmpleadosDoble : DirectorioEmpleados, FichasPersonal {

    private val estados = mutableMapOf<EntityId, EstadoEmpleado>()

    fun registrarActivo(id: EntityId) { estados[id] = EstadoEmpleado.ACTIVO }

    fun registrarInactivo(id: EntityId) { estados[id] = EstadoEmpleado.INACTIVO }

    fun olvidar(id: EntityId) { estados.remove(id) }

    override fun estado(empleadoId: EntityId): EstadoEmpleado? = estados[empleadoId]

    override fun existentes(empleadoIds: Collection<EntityId>): Set<EntityId> =
        empleadoIds.filter { estados.containsKey(it) }.toSet()

    // Feature 005: the FichasPersonal side. A staff record created here is also
    // registered as an active person, as the real implementation's would be.
    private val porDocumento = mutableMapOf<String, EntityId>()
    val altas = mutableListOf<AltaFichaPersonal>()

    fun registrarFicha(documento: String, id: EntityId = UUID.randomUUID()): EntityId {
        porDocumento[NifValidator.normalizar(documento)] = id
        registrarActivo(id)
        return id
    }

    override fun buscarPorDocumento(documento: String): EntityId? = porDocumento[NifValidator.normalizar(documento)]

    override fun crear(alta: AltaFichaPersonal): EntityId {
        require(alta.tipoContrato in setOf("JORNADA_COMPLETA", "PARCIAL", "POR_HORAS"))
        altas += alta
        return registrarFicha(alta.documento)
    }
}

@TestConfiguration
class DirectorioEmpleadosDobleConfig {
    // One instance behind both contracts, so a staff record created through
    // FichasPersonal is visible to DirectorioEmpleados - as in production, where
    // both read the same table.
    @Bean
    fun directorioEmpleados() = DirectorioEmpleadosDoble()
}
