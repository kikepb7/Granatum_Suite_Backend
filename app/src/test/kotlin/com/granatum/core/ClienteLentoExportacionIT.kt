package com.granatum.core

import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D-002, D-003: a client that stops reading does not keep a database
 * connection for ever, and its export is recorded as interrupted.
 *
 * An export holds one connection for as long as it writes. A client that reads
 * the first bytes and then stalls leaves the writing thread blocked on the
 * socket - and the transaction timeout does **not** help: it applies to
 * queries, and a thread blocked writing issues none (finding H2 of the
 * analysis). What frees the connection is what this test measures.
 *
 * ## Measured (2026-10-07)
 *
 * Tomcat's **write** timeout, which is `server.tomcat.connection-timeout`. At
 * its implicit 60 s a stalled client held the connection for 65 s and this
 * test failed. Set explicitly to 10 s in `application.yml`, the connection
 * comes back after about 15 s: the blocked write fails, the export is recorded
 * as interrupted, the transaction ends. This test runs with that production
 * value, not an override, so raising it past the bound turns it red.
 *
 * Its own Postgres: it seeds tens of thousands of shifts, which must not land
 * in the developer's database the other `app` tests run against.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClienteLentoExportacionIT {

    companion object {
        const val TIMEOUT_SEGUNDOS = 5L

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.url", postgres::getJdbcUrl)
            registry.add("spring.flyway.user", postgres::getUsername)
            registry.add("spring.flyway.password", postgres::getPassword)
            registry.add("timetracking.exportacion.timeout-segundos") { TIMEOUT_SEGUNDOS.toString() }
        }
    }

    @LocalServerPort
    var puerto: Int = 0

    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var dataSource: DataSource

    private val pool get() = (dataSource as HikariDataSource).hikariPoolMXBean

    /** One person with [n] short shifts, half an hour apart from 2024-01-01: several megabytes of CSV. */
    private fun sembrar(n: Int): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.createStatement().use { s ->
                s.execute(
                    """
                    INSERT INTO empleados (id, nombre, documento_identidad, puesto, tipo_contrato,
                                           fecha_alta, activo, created_at, updated_at)
                    VALUES ('$id', 'Persona Lenta', 'L${id.toString().take(8)}', 'Florista',
                            'JORNADA_COMPLETA', '2020-01-01', TRUE, now(), now())
                    """.trimIndent()
                )
                s.execute(
                    """
                    INSERT INTO fichajes (id, empleado_id, entrada, salida, estado, minutos_trabajados,
                                          fue_incompleto, created_at, updated_at)
                    SELECT gen_random_uuid(), '$id',
                           TIMESTAMPTZ '2024-01-01 08:00:00+01' + n * interval '30 minutes',
                           TIMESTAMPTZ '2024-01-01 08:20:00+01' + n * interval '30 minutes',
                           'CERRADO', 20, FALSE, now(), now()
                      FROM generate_series(0, ${n - 1}) AS n
                    """.trimIndent()
                )
            }
        }
        return id
    }

    private fun interrumpida(empleadoId: UUID): Boolean? =
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT completada FROM exportaciones WHERE empleado_id = ?").use { s ->
                s.setObject(1, empleadoId)
                s.executeQuery().use { r -> if (r.next()) !r.getBoolean(1) else null }
            }
        }

    @Test
    fun `a client that stops reading gives the connection back and is recorded as interrupted`() {
        val empleadoId = sembrar(40_000)
        val token = jwtService.generateAccessToken(UUID.randomUUID(), Role.ADMIN)
        val enReposo = pool.activeConnections

        Socket().use { socket ->
            // A small window, so the server's writes block soon after we stop reading.
            socket.receiveBufferSize = 4096
            socket.connect(InetSocketAddress("localhost", puerto))
            socket.getOutputStream().write(
                ("GET /api/fichajes/export?desde=2024-01-01&hasta=2026-12-31&empleadoId=$empleadoId HTTP/1.1\r\n" +
                    "Host: localhost\r\nAuthorization: Bearer $token\r\n\r\n").toByteArray()
            )
            val inicio = ByteArray(2048)
            var leidos = 0
            while (leidos < inicio.size) {
                val n = socket.getInputStream().read(inicio, leidos, inicio.size - leidos)
                if (n < 0) break
                leidos += n
            }
            assertTrue(String(inicio, 0, leidos).startsWith("HTTP/1.1 200"), String(inicio, 0, leidos).take(200))

            // From here on, the client reads nothing and keeps the socket open.
            //
            // Done means BOTH: the connection back in the pool and the export
            // recorded as interrupted. Checking the pool alone raced: right
            // after the blocked write fails and the connection comes back, the
            // server briefly takes another one to record the interruption
            // (D-003), and an assertion landing in that instant saw 1 active
            // connection although nothing was held by the stalled client
            // (measured: released after 15.2 s, then "expected 0 but was 1").
            val empiezo = System.nanoTime()
            val limite = empiezo + (TIMEOUT_SEGUNDOS + 15) * 1_000_000_000
            var anotada: Boolean? = null
            while (System.nanoTime() < limite) {
                if (pool.activeConnections == enReposo) {
                    anotada = interrumpida(empleadoId)
                    if (anotada != null && pool.activeConnections == enReposo) break
                }
                Thread.sleep(200)
            }
            val segundos = (System.nanoTime() - empiezo) / 1_000_000_000.0

            println("ClienteLento: conexion devuelta tras %.1f s (activas=%d)".format(segundos, pool.activeConnections))
            assertEquals(
                enReposo,
                pool.activeConnections,
                "the connection must return to the pool within timeout-segundos + 15 s, not stay with a stalled client"
            )
            assertEquals(true, anotada, "recorded, and as interrupted (D-003)")
        }
    }
}
