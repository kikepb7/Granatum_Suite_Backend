package com.granatum.core

import com.granatum.core.domain.contract.NewStaffRecord
import com.granatum.core.domain.contract.StaffRecords
import com.granatum.core.domain.type.ContractType
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * This module's side of the `StaffRecords` contract, which is how `auth`
 * turns an approved sign-up into a staff record without depending on
 * `timetracking` (feature 005, constitution principle I).
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class StaffRecordsJpaIT {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("timetracking.incomplete.cron") { "0 0 4 1 1 *" }
            registry.add("timetracking.retention.cron") { "0 0 4 1 1 *" }
        }

        /** A random DNI with its correct control letter. */
        fun validDni(): String {
            val number = Random.nextInt(10_000_000, 99_999_999)
            return "$number${"TRWAGMYFPDXBNJZSQVHLCKE"[number % 23]}"
        }
    }

    @Autowired lateinit var staffRecords: StaffRecords
    @Autowired lateinit var employees: EmployeeRepository

    private fun newRecord(document: String, type: String = "PART_TIME") = NewStaffRecord(
        name = "Persona registrada",
        identityDocument = document,
        position = "Florista",
        contractType = type,
        hireDate = LocalDate.of(2026, 10, 1)
    )

    @Test
    fun `create leaves an active staff record with the given data`() {
        val id = staffRecords.create(newRecord(validDni()))

        val record = employees.findById(id).orElseThrow()
        assertTrue(record.active)
        assertEquals(ContractType.PART_TIME, record.contractType)
        assertEquals("Florista", record.position)
        assertEquals(LocalDate.of(2026, 10, 1), record.hireDate)
    }

    @Test
    fun `findByDocument normalises, so a hyphen or a lowercase letter still finds the record`() {
        val dni = validDni()
        val id = staffRecords.create(newRecord(dni))

        val writtenDifferently = dni.dropLast(1) + "-" + dni.last().lowercaseChar()
        assertEquals(id, staffRecords.findByDocument(writtenDifferently))
    }

    @Test
    fun `findByDocument answers null when nobody has that document`() {
        assertNull(staffRecords.findByDocument(validDni()))
    }

    @Test
    fun `an unknown contract type is refused rather than defaulted`() {
        assertThrows<IllegalArgumentException> { staffRecords.create(newRecord(validDni(), type = "INDEFINIDO")) }
    }
}
