package com.granatum.core

import com.granatum.core.ClientePruebaHttp.Companion.campo
import com.granatum.core.ClientePruebaHttp.Companion.dniValido
import com.granatum.core.ClientePruebaHttp.Companion.haceMinutos
import com.granatum.core.api.util.RangoFechas
import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import java.time.LocalDate
import org.springframework.web.client.RestClient
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The export over a real socket, with the real security chain (feature 003).
 *
 * ## The failure this was started for
 *
 * A [org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody]
 * ends with an **async dispatch** that runs the security chain again.
 * `JwtAuthFilter` skips async dispatches, so that second pass was anonymous and
 * was denied - after the whole file had gone out. The client saw the
 * connection close mid-response (`I/O error ... closed`), and every
 * `timetracking` test stayed green because none of them goes through HTTP.
 * Fixed in `SecurityConfig` by permitting `DispatcherType.ASYNC`; this class
 * fails without it.
 *
 * T056 extends this class with the rest of the byte-level checks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExportacionHttpIT {

    @LocalServerPort
    var puerto: Int = 0

    @Autowired
    lateinit var jwtService: JwtService

    private val http by lazy { ClientePruebaHttp(puerto) }
    private val cliente by lazy { RestClient.builder().baseUrl("http://localhost:$puerto").build() }

    private data class Descarga(val estado: Int, val cabeceras: HttpHeaders, val cuerpo: ByteArray)

    private fun descargar(ruta: String, token: String): Descarga =
        cliente.get().uri(ruta)
            .header("Authorization", "Bearer $token")
            .exchange({ _, r -> Descarga(r.statusCode.value(), r.headers, r.body.readAllBytes()) }, false)!!

    private fun verificar(bytes: ByteArray): ClientePruebaHttp.Respuesta =
        cliente.post().uri("/api/exportaciones/verificar")
            .header("Authorization", "Bearer ${jwtService.generateAccessToken(UUID.randomUUID(), Role.ADMIN)}")
            .contentType(MediaType("text", "csv"))
            .body(bytes)
            .exchange({ _, r -> ClientePruebaHttp.Respuesta(r.statusCode.value(), r.body.readAllBytes().decodeToString()) }, false)!!

    /** A person with one closed shift today, clocked through the API. */
    private fun conJornadaDeHoy(documento: String): UUID {
        val id = nuevoEmpleado(documento)
        val token = jwtService.generateAccessToken(id, Role.EMPLEADO)
        val entrada = http.post(
            "/api/fichajes/entrada",
            """{"clientEventId":"${UUID.randomUUID()}","occurredAt":"${haceMinutos(90)}"}""",
            token
        )
        assertEquals(201, entrada.estado, entrada.cuerpo)
        val salida = http.post(
            "/api/fichajes/${campo(entrada.cuerpo, "id")}/salida",
            """{"clientEventId":"${UUID.randomUUID()}","occurredAt":"${haceMinutos(1)}"}""",
            token
        )
        assertEquals(200, salida.estado, salida.cuerpo)
        return id
    }

    /** A window around today wide enough for a shift that started 90 minutes ago. */
    private fun rangoDeHoy(): String {
        val hoy = LocalDate.now(RangoFechas.ZONA)
        return "desde=${hoy.minusDays(1)}&hasta=${hoy.plusDays(1)}"
    }

    private fun nuevoEmpleado(documento: String = dniValido()): UUID {
        val admin = jwtService.generateAccessToken(UUID.randomUUID(), Role.ADMIN)
        val alta = http.post(
            "/api/empleados",
            """{"nombre":"Persona HTTP","documentoIdentidad":"$documento",
                "puesto":"Florista","tipoContrato":"JORNADA_COMPLETA","fechaAlta":"2026-10-01"}""",
            admin
        )
        assertEquals(201, alta.estado, alta.cuerpo)
        return UUID.fromString(campo(alta.cuerpo, "id")!!)
    }

    @Test
    fun `a person downloads their own register over HTTP, whole`() {
        val id = nuevoEmpleado()

        val r = descargar(
            "/api/fichajes/export?desde=2026-10-01&hasta=2026-10-31",
            jwtService.generateAccessToken(id, Role.EMPLEADO)
        )

        assertEquals(200, r.estado)
        assertEquals("text/csv;charset=UTF-8", r.cabeceras.contentType.toString())
        assertEquals(
            "registro-jornada_${id}_2026-10-01_2026-10-31.csv",
            r.cabeceras.contentDisposition.filename,
            "the person's id and the range, never the name (D-017)"
        )
        assertNull(r.cabeceras.getFirst("X-Registro-Disponible-Desde"), "only when trimmed")
        assertContentEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), r.cuerpo.copyOfRange(0, 3))
        assertTrue(String(r.cuerpo, Charsets.UTF_8).endsWith("Correcciones\r\n"), "the whole header arrived")
    }

    /** SC-001: an EMPLEADO gets their own register over HTTP. */
    @Test
    fun `an EMPLEADO downloads their own shift`() {
        val documento = dniValido()
        val id = conJornadaDeHoy(documento)

        val r = descargar("/api/fichajes/export?${rangoDeHoy()}", jwtService.generateAccessToken(id, Role.EMPLEADO))

        assertEquals(200, r.estado)
        val lineas = String(r.cuerpo, Charsets.UTF_8).removePrefix("\uFEFF").trimEnd().split("\r\n")
        assertEquals(2, lineas.size, "the header and today's shift")
        assertTrue(lineas[1].startsWith("Persona HTTP;$documento;Florista;"), lineas[1])
        assertTrue(lineas[1].contains(";CERRADO;"))
    }

    /** FR-012 over HTTP: the role comes from the token, and decides the columns. */
    @Test
    fun `REPRESENTANTE downloads without the document column`() {
        val documento = dniValido()
        val id = conJornadaDeHoy(documento)

        val r = descargar(
            "/api/fichajes/export?${rangoDeHoy()}&empleadoId=$id",
            jwtService.generateAccessToken(UUID.randomUUID(), Role.REPRESENTANTE)
        )

        assertEquals(200, r.estado)
        val texto = String(r.cuerpo, Charsets.UTF_8)
        assertFalse(texto.contains("Documento"), "no column header")
        assertFalse(texto.contains(documento), "and no document anywhere")
        assertTrue(texto.contains("Persona HTTP;Florista;"))
    }

    /** FR-028, SC-008: the downloaded bytes verify; one character changed does not. */
    @Test
    fun `a downloaded file verifies, and an altered one does not`() {
        val id = conJornadaDeHoy(dniValido())
        val bytes = descargar("/api/fichajes/export?${rangoDeHoy()}", jwtService.generateAccessToken(id, Role.EMPLEADO)).cuerpo

        val ok = verificar(bytes)
        assertEquals(200, ok.estado, ok.cuerpo)
        assertEquals("true", campo(ok.cuerpo, "coincide"))

        val alterado = bytes.copyOf().also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() }
        val mal = verificar(alterado)
        assertEquals(200, mal.estado, mal.cuerpo)
        assertEquals("false", campo(mal.cuerpo, "coincide"))
    }

    @Test
    fun `a range reaching past the retention period says where data starts`() {
        val id = nuevoEmpleado()

        val r = descargar(
            "/api/fichajes/export?desde=2015-01-01&hasta=2026-10-31&empleadoId=$id",
            jwtService.generateAccessToken(UUID.randomUUID(), Role.ENCARGADO)
        )

        assertEquals(200, r.estado)
        val disponible = r.cabeceras.getFirst("X-Registro-Disponible-Desde")!!
        assertEquals("registro-jornada_${id}_${disponible}_2026-10-31.csv", r.cabeceras.contentDisposition.filename)
    }

    @Test
    fun `the monthly download ends with its block and is named by id and month`() {
        val id = nuevoEmpleado()

        val r = descargar(
            "/api/fichajes/empleado/$id/resumen/descarga?anio=2026&mes=3",
            jwtService.generateAccessToken(UUID.randomUUID(), Role.REPRESENTANTE)
        )

        assertEquals(200, r.estado)
        assertEquals("registro-mensual_${id}_2026-03.csv", r.cabeceras.contentDisposition.filename)
        val texto = String(r.cuerpo, Charsets.UTF_8)
        assertTrue(texto.contains("\r\n\r\nTotal del mes;"), "a blank line, then the total")
        assertTrue(texto.contains("\r\nTipo de contrato;JORNADA_COMPLETA;"))
        assertTrue(texto.endsWith("\r\n"))

        val fuera = http.get(
            "/api/fichajes/empleado/$id/resumen/descarga?anio=2026&mes=13",
            jwtService.generateAccessToken(id, Role.EMPLEADO)
        )
        assertEquals(422, fuera.estado, fuera.cuerpo)
        assertEquals("VALORES_INCOHERENTES", campo(fuera.cuerpo, "code"))
    }

    @Test
    fun `refusals are settled before the body, with the contract's codes`() {
        val id = nuevoEmpleado()
        val propio = jwtService.generateAccessToken(id, Role.EMPLEADO)

        val ajeno = http.get("/api/fichajes/export?desde=2026-10-01&hasta=2026-10-31&empleadoId=${UUID.randomUUID()}", propio)
        assertEquals(403, ajeno.estado, ajeno.cuerpo)
        assertEquals("FORBIDDEN", campo(ajeno.cuerpo, "code"))

        val invertido = http.get("/api/fichajes/export?desde=2026-10-31&hasta=2026-10-01", propio)
        assertEquals(422, invertido.estado, invertido.cuerpo)
        assertEquals("VALORES_INCOHERENTES", campo(invertido.cuerpo, "code"))

        val formato = http.get("/api/fichajes/export?formato=pdf&desde=2026-10-01&hasta=2026-10-31", propio)
        assertEquals(422, formato.estado, formato.cuerpo)
        assertEquals("VALORES_INCOHERENTES", campo(formato.cuerpo, "code"))
    }
}
