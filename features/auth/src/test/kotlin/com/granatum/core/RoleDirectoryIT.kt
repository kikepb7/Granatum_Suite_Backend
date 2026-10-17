package com.granatum.core

import com.granatum.core.domain.contract.RoleDirectory
import com.granatum.core.domain.type.Role
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** This module's side of the `RoleDirectory` contract (feature 008). */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class RoleDirectoryIT : BaseAuthIT() {

    @Autowired lateinit var roles: RoleDirectory

    @Test
    fun `answers the staff ids of the accounts holding the roles asked for, and only those`() {
        val admin = activeAccount(role = Role.ADMIN)
        val manager = activeAccount(role = Role.MANAGER)
        val employee = activeAccount(role = Role.EMPLOYEE)

        val managers = roles.employeesWithRole(setOf(Role.ADMIN, Role.MANAGER))

        assertTrue(admin.employeeId in managers && manager.employeeId in managers)
        assertTrue(employee.employeeId !in managers)
        assertTrue(admin.employeeId in roles.employeesWithRole(setOf(Role.ADMIN)))
        assertTrue(manager.employeeId !in roles.employeesWithRole(setOf(Role.ADMIN)))
    }

    @Test
    fun `no roles, nobody`() {
        assertEquals(emptySet(), roles.employeesWithRole(emptySet()))
    }
}
