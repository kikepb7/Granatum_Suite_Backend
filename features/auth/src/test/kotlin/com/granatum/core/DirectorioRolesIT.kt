package com.granatum.core

import com.granatum.core.domain.contract.DirectorioRoles
import com.granatum.core.domain.type.Role
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** This module's side of the `DirectorioRoles` contract (feature 008). */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class DirectorioRolesIT : BaseAuthIT() {

    @Autowired lateinit var roles: DirectorioRoles

    @Test
    fun `answers the staff ids of the accounts holding the roles asked for, and only those`() {
        val admin = cuentaActiva(rol = Role.ADMIN)
        val encargado = cuentaActiva(rol = Role.ENCARGADO)
        val empleado = cuentaActiva(rol = Role.EMPLEADO)

        val gestores = roles.empleadosConRol(setOf(Role.ADMIN, Role.ENCARGADO))

        assertTrue(admin.empleadoId in gestores && encargado.empleadoId in gestores)
        assertTrue(empleado.empleadoId !in gestores)
        assertTrue(admin.empleadoId in roles.empleadosConRol(setOf(Role.ADMIN)))
        assertTrue(encargado.empleadoId !in roles.empleadosConRol(setOf(Role.ADMIN)))
    }

    @Test
    fun `no roles, nobody`() {
        assertEquals(emptySet(), roles.empleadosConRol(emptySet()))
    }
}
