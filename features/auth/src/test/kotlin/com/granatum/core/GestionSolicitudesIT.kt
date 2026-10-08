package com.granatum.core

import com.granatum.core.domain.exception.SolicitudNoEncontradaException
import com.granatum.core.domain.exception.SolicitudNoPendienteException
import com.granatum.core.domain.type.EstadoSolicitudRegistro
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.TipoEventoSeguridad
import com.granatum.core.service.DatosFicha
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.web.client.RestClient
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The ADMIN's list and rejection (feature 005, US4): FR-015, FR-016, FR-022, FR-023, FR-026. */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SeguridadPermisivaTestConfig::class)
class GestionSolicitudesIT : BaseRegistroIT() {

    @LocalServerPort
    var puerto: Int = 0

    private val ficha = DatosFicha("Florista", "POR_HORAS", LocalDate.of(2026, 10, 1))

    @Test
    fun `the list brings only pending requests, oldest first, and says whether a staff record exists`() {
        val documentoConFicha = dniValido()
        val existente = directorio.registrarFicha(documentoConFicha)
        val (primera, _) = pendiente(documento = documentoConFicha)
        val (segunda, _) = pendiente()
        val (rechazada, _) = pendiente()
        aprobacion.rechazar(rechazada.id, adminId)

        val lista = aprobacion.listarPendientes()

        assertEquals(listOf(primera.id, segunda.id), lista.map { it.id })
        assertEquals(existente, lista[0].empleadoExistenteId)
        assertNull(lista[1].empleadoExistenteId)
    }

    /** FR-016: the JSON the ADMIN receives has neither the password nor the code. */
    @Test
    fun `the listed JSON carries no password or code`() {
        pendiente()

        val json = RestClient.create("http://localhost:$puerto").get().uri("/api/auth/registros")
            .retrieve().body(String::class.java)!!

        assertTrue(json.contains("\"empleadoExistenteId\""), json)
        listOf("password", "codigo", "hash", "argon2").forEach {
            assertFalse(json.contains(it, ignoreCase = true), "the list must not mention $it: $json")
        }
    }

    @Test
    fun `rejecting resolves the request, records who did it and empties its personal data`() {
        val (solicitud, _) = pendiente()
        val antes = eventos.countByTipo(TipoEventoSeguridad.REGISTRO_RECHAZADO)

        aprobacion.rechazar(solicitud.id, adminId)

        val resuelta = solicitudes.findById(solicitud.id).orElseThrow()
        assertEquals(EstadoSolicitudRegistro.RECHAZADA, resuelta.estado)
        assertEquals(adminId, resuelta.resueltaPor)
        listOf(resuelta.email, resuelta.nombre, resuelta.documentoIdentidad, resuelta.passwordHash, resuelta.codigoHash)
            .forEach { assertNull(it) }
        assertEquals(antes + 1, eventos.countByTipo(TipoEventoSeguridad.REGISTRO_RECHAZADO))
    }

    @Test
    fun `a resolved request cannot be resolved again`() {
        val (solicitud, codigo) = pendiente()
        aprobacion.rechazar(solicitud.id, adminId)

        assertFailsWith<SolicitudNoPendienteException> { aprobacion.rechazar(solicitud.id, adminId) }
        assertFailsWith<SolicitudNoPendienteException> {
            aprobacion.aprobar(solicitud.id, codigo, Role.EMPLEADO, ficha, adminId)
        }
    }

    @Test
    fun `an unknown request is not found`() {
        assertFailsWith<SolicitudNoEncontradaException> { aprobacion.rechazar(UUID.randomUUID(), adminId) }
    }

    /** FR-022: approving the real one cancels the impostor's, by address or by document. */
    @Test
    fun `approving one request cancels the others of the same person`() {
        val email = correoUnico()
        val documento = dniValido()
        val (real, codigo) = pendiente(email, documento)
        val (mismoCorreo, _) = pendiente(email)
        val (mismoDocumento, _) = pendiente(documento = documento)
        val (ajena, _) = pendiente()

        aprobacion.aprobar(real.id, codigo, Role.EMPLEADO, ficha, adminId)

        assertEquals(EstadoSolicitudRegistro.ANULADA, solicitudes.findById(mismoCorreo.id).orElseThrow().estado)
        assertEquals(EstadoSolicitudRegistro.ANULADA, solicitudes.findById(mismoDocumento.id).orElseThrow().estado)
        assertNull(solicitudes.findById(mismoCorreo.id).orElseThrow().email)
        assertEquals(EstadoSolicitudRegistro.PENDIENTE, solicitudes.findById(ajena.id).orElseThrow().estado)
    }
}
