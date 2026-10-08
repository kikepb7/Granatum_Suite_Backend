package com.granatum.core

import com.granatum.core.domain.event.AvisoDominio
import com.granatum.core.domain.event.TipoAviso
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.repositories.NotificacionRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Pageable
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Who gets told (feature 008, US2): FR-003, FR-004, FR-008. Events are published
 * the way the features publish them - through Spring - inside or outside a
 * transaction.
 */
@Testcontainers
@SpringBootTest(classes = [NotificationsTestApplication::class])
class DestinatariosIT : BaseNotificacionesIT() {

    @Autowired lateinit var publicador: ApplicationEventPublisher
    @Autowired lateinit var notificaciones: NotificacionRepository
    @Autowired lateinit var transacciones: TransactionTemplate

    @BeforeEach
    fun sinRoles() = directorioRoles.olvidarTodos()

    private fun tipos(destinatario: UUID) =
        notificaciones.findAllByDestinatarioIdOrderByCreadaEnDescIdDesc(destinatario, Pageable.ofSize(100)).map { it.tipo to it.referenciaId }

    private fun publicarEnTransaccion(aviso: AvisoDominio) = transacciones.executeWithoutResult { publicador.publishEvent(aviso) }

    @Test
    fun `a pending absence reaches every ENCARGADO and ADMIN but not whoever asked`() {
        val admin = directorioRoles.con(Role.ADMIN)
        val encargado = directorioRoles.con(Role.ENCARGADO)
        val encargadoQuePide = directorioRoles.con(Role.ENCARGADO)
        val empleado = directorioRoles.con(Role.EMPLEADO)
        val ausencia = UUID.randomUUID()

        publicarEnTransaccion(AvisoDominio(TipoAviso.AUSENCIA_PENDIENTE, ausencia, titularId = encargadoQuePide, autorId = encargadoQuePide))

        assertEquals(listOf(TipoAviso.AUSENCIA_PENDIENTE to ausencia), tipos(admin))
        assertEquals(listOf(TipoAviso.AUSENCIA_PENDIENTE to ausencia), tipos(encargado))
        assertTrue(tipos(encargadoQuePide).isEmpty(), "nobody is told about their own request")
        assertTrue(tipos(empleado).isEmpty())
    }

    @Test
    fun `a pending correction reaches the managers too`() {
        val encargado = directorioRoles.con(Role.ENCARGADO)
        val correccion = UUID.randomUUID()

        publicarEnTransaccion(AvisoDominio(TipoAviso.CORRECCION_PENDIENTE, correccion, autorId = UUID.randomUUID()))

        assertEquals(listOf(TipoAviso.CORRECCION_PENDIENTE to correccion), tipos(encargado))
    }

    /** FR-004. Published without a transaction, as the sign-up does (fallbackExecution). */
    @Test
    fun `a pending sign-up reaches the ADMINs only, even published outside a transaction`() {
        val admin = directorioRoles.con(Role.ADMIN)
        val encargado = directorioRoles.con(Role.ENCARGADO)
        val solicitud = UUID.randomUUID()

        publicador.publishEvent(AvisoDominio(TipoAviso.REGISTRO_PENDIENTE, solicitud))

        assertEquals(listOf(TipoAviso.REGISTRO_PENDIENTE to solicitud), tipos(admin))
        assertTrue(tipos(encargado).isEmpty())
    }

    @Test
    fun `resolutions and shift notices reach the person they concern`() {
        val persona = UUID.randomUUID()
        val ausencia = UUID.randomUUID()
        val fichaje = UUID.randomUUID()

        publicarEnTransaccion(AvisoDominio(TipoAviso.AUSENCIA_APROBADA, ausencia, titularId = persona, autorId = UUID.randomUUID()))
        publicarEnTransaccion(AvisoDominio(TipoAviso.FICHAJE_SIN_SALIDA, fichaje, titularId = persona))

        assertEquals(setOf(TipoAviso.AUSENCIA_APROBADA to ausencia, TipoAviso.FICHAJE_SIN_SALIDA to fichaje), tipos(persona).toSet())
    }

    @Test
    fun `with no ENCARGADO or ADMIN nobody is told, and that is not an error`() {
        publicarEnTransaccion(AvisoDominio(TipoAviso.AUSENCIA_PENDIENTE, UUID.randomUUID(), autorId = UUID.randomUUID()))
    }

    /** FR-008: no notice of something that did not happen. */
    @Test
    fun `an event published in a transaction that rolls back leaves no notice`() {
        val admin = directorioRoles.con(Role.ADMIN)

        runCatching {
            transacciones.executeWithoutResult {
                publicador.publishEvent(AvisoDominio(TipoAviso.AUSENCIA_PENDIENTE, UUID.randomUUID(), autorId = UUID.randomUUID()))
                error("the operation fails after publishing")
            }
        }

        assertTrue(tipos(admin).isEmpty())
    }

    /**
     * FR-008, second half: the operation is already committed, so a notice
     * that cannot be created is lost - it must not turn into an error for
     * whoever made the request.
     */
    @Test
    fun `a notice that fails to be created does not reach the caller`() {
        directorioRoles.fallar = true

        publicarEnTransaccion(AvisoDominio(TipoAviso.AUSENCIA_PENDIENTE, UUID.randomUUID(), autorId = UUID.randomUUID()))
        // Without a transaction (fallbackExecution, as sign-up publishes) the
        // listener runs inside publishEvent itself: this is the case the catch
        // exists for - after a commit, Spring already contains the exception.
        publicador.publishEvent(AvisoDominio(TipoAviso.REGISTRO_PENDIENTE, UUID.randomUUID()))
    }

    /** FR-007, D-002: the forgotten clock-out check sees the same open shift again and again. */
    @Test
    fun `the same notice published twice is stored once`() {
        val persona = UUID.randomUUID()
        val fichaje = UUID.randomUUID()

        repeat(2) { publicarEnTransaccion(AvisoDominio(TipoAviso.FICHAJE_SIN_SALIDA, fichaje, titularId = persona)) }

        assertEquals(1, tipos(persona).size)
    }
}
