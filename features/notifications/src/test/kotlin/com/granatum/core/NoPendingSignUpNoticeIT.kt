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
 * and the database no longer accepts a "pending sign-up" notice - neither under
 * its old name (`REGISTRO_PENDIENTE`) nor under an English one (V33).
 */
@Testcontainers
@SpringBootTest(classes = [NotificationsTestApplication::class])
class NoPendingSignUpNoticeIT : BaseNotificationsIT() {

    @Autowired lateinit var jdbc: JdbcTemplate

    @Test
    fun `a pending sign-up notice is refused by the database`() {
        listOf("REGISTRO_PENDIENTE", "SIGN_UP_PENDING").forEach { type ->
            assertFailsWith<DataIntegrityViolationException> {
                jdbc.update(
                    "INSERT INTO notifications (id, recipient_id, type, reference_id, created_at) " +
                        "VALUES (gen_random_uuid(), gen_random_uuid(), ?, gen_random_uuid(), now())",
                    type
                )
            }
        }
    }

    @Test
    fun `a Spanish type value is refused by the database`() {
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                "INSERT INTO notifications (id, recipient_id, type, reference_id, created_at) " +
                    "VALUES (gen_random_uuid(), gen_random_uuid(), 'AUSENCIA_PENDIENTE', gen_random_uuid(), now())"
            )
        }
    }
}
