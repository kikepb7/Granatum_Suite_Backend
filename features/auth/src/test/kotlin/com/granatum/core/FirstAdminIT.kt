package com.granatum.core

import com.granatum.core.domain.exception.InvalidBootstrapCodeException
import com.granatum.core.domain.exception.EmailAlreadyRegisteredException
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.SecurityEventType
import com.granatum.core.infrastructure.database.repositories.SecurityEventRepository
import com.granatum.core.service.AuthenticationService
import com.granatum.core.service.SignUpData
import com.granatum.core.service.SignUpService
import com.granatum.core.service.CreatedAdmin
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The first ADMIN of a fresh installation - the business owner, the only one
 * who signs up (feature 005 US1, feature 009): FR-009 to FR-013.
 *
 * Every test starts from "no ADMIN account": the shared container is reused by
 * other classes, so existing ADMIN accounts are demoted first. Demoting rather
 * than deleting because accounts has no delete path anywhere, tests
 * included, and nothing else here depends on those accounts' role.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
@TestPropertySource(properties = ["auth.registro.codigo-arranque=${FirstAdminIT.CODE}"])
class FirstAdminIT : BaseAuthIT() {

    companion object {
        const val CODE = "arranque-de-prueba-0123456789"
    }

    @Autowired lateinit var signUpService: SignUpService
    @Autowired lateinit var events: SecurityEventRepository
    @Autowired lateinit var authentication: AuthenticationService
    @Autowired lateinit var jdbc: JdbcTemplate

    private val password = "Granatum-Jefe-2026!"

    @BeforeEach
    fun withoutAdmin() {
        jdbc.update("UPDATE accounts SET role = 'EMPLOYEE' WHERE role = 'ADMIN'")
    }

    private fun input(
        email: String = uniqueEmail(),
        identityDocument: String = validDni(),
        code: String = CODE
    ) = SignUpData(email, password, "Jefa de Granatum", identityDocument, code)

    @Test
    fun `the right code with no ADMIN creates the ADMIN account and a staff record`() {
        val email = uniqueEmail()
        val identityDocument = validDni()

        val result = signUpService.signUp(input(email, identityDocument))

        val created = assertIs<CreatedAdmin>(result)
        val account = accounts.findByEmail(email)!!
        assertEquals(Role.ADMIN, account.role)
        assertFalse(account.passwordChangeRequired, "the person chose this password")
        assertEquals(created.employeeId, account.employeeId)

        val staffRecord = directory.createdRecords.last()
        assertEquals("Dirección", staffRecord.position)
        assertEquals("FULL_TIME", staffRecord.contractType)
        assertEquals(LocalDate.now(ZoneId.of("Europe/Madrid")), staffRecord.hireDate)

        // And the password works straight away.
        authentication.signIn(email, password)
        assertTrue(
            events.findAllByAccountIdOrderByOccurredAtDesc(account.id)
                .any { it.type == SecurityEventType.INITIAL_ADMIN_CREATED }
        )
    }

    @Test
    fun `an existing staff record with that document is linked instead of duplicated`() {
        val identityDocument = validDni()
        val existing = directory.registerStaffRecord(identityDocument)
        val recordsBefore = directory.createdRecords.size

        val result = assertIs<CreatedAdmin>(signUpService.signUp(input(identityDocument = identityDocument)))

        assertEquals(existing, result.employeeId)
        assertEquals(recordsBefore, directory.createdRecords.size, "no second staff record")
    }

    @Test
    fun `once an ADMIN exists the code stops working, even the right one`() {
        signUpService.signUp(input())
        val email = uniqueEmail()

        assertFailsWith<InvalidBootstrapCodeException> { signUpService.signUp(input(email)) }
        assertEquals(null, accounts.findByEmail(email))
    }

    @Test
    fun `an ADMIN created by the temporary-password route also closes the bootstrap`() {
        activeAccount(role = Role.ADMIN)

        assertFailsWith<InvalidBootstrapCodeException> { signUpService.signUp(input()) }
    }

    @Test
    fun `a wrong code creates nothing and leaves a security event`() {
        val email = uniqueEmail()

        val before = events.countByType(SecurityEventType.BOOTSTRAP_REJECTED)

        assertFailsWith<InvalidBootstrapCodeException> { signUpService.signUp(input(email, code = "otro-codigo")) }

        assertEquals(null, accounts.findByEmail(email))
        assertEquals(before + 1, events.countByType(SecurityEventType.BOOTSTRAP_REJECTED))
    }

    @Test
    fun `an address that already has an account is refused`() {
        val existing = activeAccount()

        assertFailsWith<EmailAlreadyRegisteredException> { signUpService.signUp(input(existing.email)) }
        assertEquals(Role.EMPLOYEE, accounts.findByEmail(existing.email)!!.role)
    }

    /**
     * Without the advisory lock both read "no ADMIN" at once and both become
     * ADMIN: two different rows, so no unique constraint stops it (D-002).
     */
    @Test
    fun `two simultaneous bootstraps with different addresses produce a single ADMIN`() {
        val output = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val results = (1..2).map {
            pool.submit<Result<CreatedAdmin>> {
                val d = input()
                output.await()
                runCatching { signUpService.signUp(d) }
            }
        }
        output.countDown()
        val outcomes = results.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, outcomes.count { it.isSuccess }, outcomes.toString())
        assertIs<InvalidBootstrapCodeException>(outcomes.single { it.isFailure }.exceptionOrNull())
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM accounts WHERE role = 'ADMIN'", Int::class.java))
    }

    private fun validDni(): String {
        val number = (10_000_000..99_999_999).random()
        return "$number${"TRWAGMYFPDXBNJZSQVHLCKE"[number % 23]}"
    }
}
