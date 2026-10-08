package com.granatum.core

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertFailsWith

/**
 * Feature 009, V25: with no sign-up requests left there is nothing to approve,
 * and the database no longer accepts a "pending sign-up" notice.
 */
@Testcontainers
@SpringBootTest(classes = [NotificationsTestApplication::class])
class SinAvisoRegistroPendienteIT : BaseNotificacionesIT() {

    @Autowired lateinit var jdbc: JdbcTemplate

    @Test
    fun `a REGISTRO_PENDIENTE notice is refused by the database`() {
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                "INSERT INTO notificaciones (id, destinatario_id, tipo, referencia_id, creada_en) " +
                    "VALUES (gen_random_uuid(), gen_random_uuid(), 'REGISTRO_PENDIENTE', gen_random_uuid(), now())"
            )
        }
    }
}
