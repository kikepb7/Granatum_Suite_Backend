package com.granatum.core

import com.granatum.core.domain.type.EstadoSolicitudRegistro
import com.granatum.core.domain.type.TipoEventoSeguridad
import com.granatum.core.scheduling.CaducidadSolicitudesJob
import com.granatum.core.service.RegistradorEventosSeguridad
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Nothing pending forever, nothing personal once resolved (feature 005, US5): FR-025 to FR-027, SC-005. */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class CaducidadSolicitudesIT : BaseRegistroIT() {

    @Autowired lateinit var registrador: RegistradorEventosSeguridad

    private val ahora: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)

    private fun jobAlReloj(instante: Instant) =
        CaducidadSolicitudesJob(solicitudes, registrador, dias = 7, clock = Clock.fixed(instante, ZoneOffset.UTC))

    private fun envejecer(id: java.util.UUID, dias: Long) {
        jdbc.update(
            "UPDATE solicitudes_registro SET creada_en = ? WHERE id = ?",
            Timestamp.from(ahora.minus(dias, ChronoUnit.DAYS)), id
        )
    }

    @Test
    fun `a request older than the limit expires and loses its personal data, a younger one stays`() {
        val (vieja, _) = pendiente()
        val (reciente, _) = pendiente()
        envejecer(vieja.id, 8)
        envejecer(reciente.id, 6)
        val antes = eventos.countByTipo(TipoEventoSeguridad.REGISTRO_CADUCADO)

        val caducadas = jobAlReloj(ahora).caducar()

        assertEquals(1, caducadas)
        val caducada = solicitudes.findById(vieja.id).orElseThrow()
        assertEquals(EstadoSolicitudRegistro.CADUCADA, caducada.estado)
        assertNull(caducada.resueltaPor, "the system resolved it, not an ADMIN")
        listOf(caducada.email, caducada.nombre, caducada.documentoIdentidad, caducada.passwordHash, caducada.codigoHash)
            .forEach { assertNull(it) }
        assertEquals(EstadoSolicitudRegistro.PENDIENTE, solicitudes.findById(reciente.id).orElseThrow().estado)
        assertEquals(antes + 1, eventos.countByTipo(TipoEventoSeguridad.REGISTRO_CADUCADO))
    }

    /**
     * SC-005 at the database: a code path that forgot to empty a field would be
     * refused rather than quietly keep someone's DNI.
     */
    @Test
    fun `the database refuses a resolved request that keeps personal data`() {
        val (solicitud, _) = pendiente()

        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                "UPDATE solicitudes_registro SET estado = 'RECHAZADA', resuelta_en = now() WHERE id = ?",
                solicitud.id
            )
        }
    }

    @Test
    fun `the database refuses a pending request missing its data`() {
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                "INSERT INTO solicitudes_registro (id, estado, creada_en) VALUES (gen_random_uuid(), 'PENDIENTE', now())"
            )
        }
    }
}
