package com.granatum.core

import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.crypto.VerificadorAcotado
import com.granatum.core.infrastructure.database.entities.CuentaAccesoEntity
import com.granatum.core.infrastructure.database.repositories.CuentaAccesoRepository
import com.granatum.core.infrastructure.database.repositories.SolicitudRegistroRepository
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import java.util.UUID

/**
 * Shared Testcontainers wiring for this module's integration tests.
 *
 * ## Why the container is not annotated `@Container`
 *
 * Because it is declared in a **base class**, and a companion object is a single
 * static shared by every subclass. Under `@Container` the JUnit extension owns
 * its lifecycle and **stops it when the first test class finishes** - while
 * Spring's context cache happily hands the same, now-dangling DataSource to the
 * next class, which then fails with "Could not open JPA EntityManager for
 * transaction". That is not theoretical: it is what happened the first time the
 * whole module's suite ran together, after each class had passed on its own.
 *
 * So the container is started once, by hand, and never stopped. Testcontainers'
 * Ryuk sidecar removes it when the JVM exits. This is the standard singleton
 * container pattern, and the reason it is spelled out here is that the broken
 * version looks more correct.
 *
 * ## Shared database, unique data
 *
 * One database for every class in the module, so these tests commit into the
 * same schema. Rather than cleaning between tests, every helper generates
 * unique values ([correoUnico], a random `empleadoId`), which is both faster and
 * closer to how the application actually behaves.
 */
@Import(DirectorioEmpleadosDobleConfig::class)
abstract class BaseAuthIT {

    companion object {
        // No explicit type: PostgreSQLContainer is self-typed
        // (`SELF : PostgreSQLContainer<SELF>`), so annotating it needs a type
        // argument and loses the inference the method references below rely on.
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine").also { it.start() }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("spring.flyway.enabled") { "true" }
        }
    }

    @Autowired lateinit var cuentas: CuentaAccesoRepository
    @Autowired lateinit var verificador: VerificadorAcotado
    @Autowired lateinit var directorio: DirectorioEmpleadosDoble
    @Autowired lateinit var solicitudesRegistro: SolicitudRegistroRepository

    /**
     * Feature 005: the pending sign-up cap is global and this container is
     * shared by every class, so one class's leftovers (the timing test leaves
     * over a hundred) would make another's sign-ups answer 503. Expired through
     * the same UPDATE the expiry job uses - never deleted.
     */
    @BeforeEach
    fun sinSolicitudesPendientesAjenas() {
        solicitudesRegistro.caducarAnterioresA(Instant.now().plusSeconds(3600), Instant.now())
    }

    protected fun correoUnico(): String = "p-${UUID.randomUUID()}@granatum.es"

    /**
     * Creates an account whose person the directory reports as active, which is
     * the normal starting point for almost every test here.
     */
    protected fun cuentaActiva(
        password: String = "Granatum-2026!",
        email: String = correoUnico(),
        rol: Role = Role.EMPLEADO,
        requiereCambio: Boolean = false,
        empleadoId: UUID = UUID.randomUUID()
    ): CuentaAccesoEntity {
        directorio.registrarActivo(empleadoId)
        return cuentas.save(
            CuentaAccesoEntity(
                empleadoId = empleadoId,
                email = email,
                rol = rol,
                passwordHash = verificador.codificar(password),
                requiereCambioPassword = requiereCambio
            )
        )
    }
}
