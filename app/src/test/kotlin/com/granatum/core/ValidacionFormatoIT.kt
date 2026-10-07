package com.granatum.core

import com.granatum.core.ClientePruebaHttp.Companion.campo
import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every validation `400` in the API carries the contract's `{code, message}`
 * shape, with `code = VALIDACION`, no stack trace and **never the rejected value**.
 *
 * ## The hole this closes
 *
 * No class in the product produced `VALIDACION`, although the `auth` contract
 * promised it. Validation and missing-parameter errors came out with Spring's
 * default error body - and in `dev`, where devtools turns on stack traces and
 * binding errors, that body carried the whole trace and the **rejected value**:
 * a malformed email came back in the response, and an over-long new password
 * in `change-password` would have come back in clear. `auth`'s tests only
 * checked the `400` status, which is why nobody saw it. Found while planning
 * feature 003 (research.md D-019).
 *
 * ## Why those properties
 *
 * devtools is `developmentOnly`, so it is absent from the test classpath and
 * the trace would not appear here even with the hole open. The properties below
 * switch on exactly what devtools switches on in `dev`, so this test proves the
 * handler wins in the environment where the leak was real - not merely in one
 * where Spring happens to be quiet.
 *
 * Every case asserts the **body**, not just the status: a test that only reads
 * the status code is what let this through the first time.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "server.error.include-stacktrace=always",
        "server.error.include-message=always",
        "server.error.include-binding-errors=always",
        "server.error.include-exception=true"
    ]
)
class ValidacionFormatoIT {

    @LocalServerPort
    var puerto: Int = 0

    @Autowired
    lateinit var jwtService: JwtService

    private val http by lazy { ClientePruebaHttp(puerto) }

    private fun token(rol: Role) = jwtService.generateAccessToken(UUID.randomUUID(), rol)

    private fun comprobar(r: ClientePruebaHttp.Respuesta, valorEnviado: String? = null) {
        assertEquals(400, r.estado, r.cuerpo)
        assertEquals(
            "VALIDACION",
            campo(r.cuerpo, "code"),
            "a validation error must use the contract's shape, not Spring's default: ${r.cuerpo.take(300)}"
        )
        assertFalse(r.cuerpo.contains("\"trace\""), "no stack trace in a response: ${r.cuerpo.take(300)}")
        assertFalse(r.cuerpo.contains("\tat "), "no stack frames in a response")
        if (valorEnviado != null) {
            assertFalse(
                r.cuerpo.contains(valorEnviado),
                "the rejected value must never be echoed back - it can be a password"
            )
        }
    }

    @Test
    fun `an invalid request body field answers VALIDACION without echoing the value`() {
        val correo = "no-es-un-correo-${UUID.randomUUID()}"

        comprobar(
            http.post("/api/auth/login", """{"email":"$correo","password":"x"}"""),
            valorEnviado = correo
        )
    }

    @Test
    fun `an over-long password is refused without echoing it`() {
        val larga = "Clave-Secreta-" + "x".repeat(140)

        comprobar(
            http.post("/api/auth/login", """{"email":"ana@granatum.es","password":"$larga"}"""),
            valorEnviado = larga
        )
    }

    @Test
    fun `a missing request parameter answers VALIDACION`() {
        comprobar(
            http.get("/api/fichajes/empleado/${UUID.randomUUID()}/resumen", token(Role.ADMIN))
        )
    }

    @Test
    fun `a parameter of the wrong type answers VALIDACION`() {
        comprobar(
            http.get(
                "/api/fichajes/empleado/${UUID.randomUUID()}/resumen?anio=no-es-un-numero&mes=1",
                token(Role.ADMIN)
            ),
            valorEnviado = "no-es-un-numero"
        )
    }

    /** Feature 003: the export's dates are request parameters like any other. */
    @Test
    fun `an export without its dates, or with a malformed one, answers VALIDACION`() {
        comprobar(http.get("/api/fichajes/export?hasta=2026-10-31", token(Role.ADMIN)))
        comprobar(
            http.get("/api/fichajes/export?desde=no-es-fecha&hasta=2026-10-31", token(Role.ADMIN)),
            valorEnviado = "no-es-fecha"
        )
    }

    /** Feature 004: invoicing's parameters and bodies answer like any other route. */
    @Test
    fun `invoicing answers VALIDACION for a missing year, no files or no reason`() {
        comprobar(http.get("/api/facturacion/reportes?periodo=ANUAL", token(Role.ADMIN)))
        comprobar(http.get("/api/facturacion/reportes?periodo=MENSUAL&anio=2026", token(Role.ADMIN)))
        comprobar(http.post("/api/facturacion/trimestres/2026/1/reabrir", "{}", token(Role.ADMIN)))
        val sinFicheros = org.springframework.web.client.RestClient.builder().baseUrl("http://localhost:$puerto").build()
            .post().uri("/api/facturacion/facturas")
            .header("Authorization", "Bearer ${token(Role.ADMIN)}")
            .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
            .body(org.springframework.util.LinkedMultiValueMap<String, Any>().apply { add("otra", "x") })
            .exchange({ _, r -> ClientePruebaHttp.Respuesta(r.statusCode.value(), r.body.readAllBytes().decodeToString()) }, false)!!
        comprobar(sinFicheros)
    }

    /**
     * Findings I1 and T2: an upload over the request limit gets a real 413 with
     * the contract's body, not a reset connection - which is what Tomcat's
     * default max-swallow-size of 2 MB can produce.
     *
     * Over a raw socket that keeps sending the body while it reads the answer,
     * as curl does. Java's HTTP clients cannot read a response that arrives
     * while they are still sending, and reported the server's correct 413 as a
     * broken connection; curl against the running application got the 413 for
     * 51, 60 and 120 MB (2026-10-08).
     */
    @Test
    fun `an upload over the limit receives 413 over HTTP`() {
        val frontera = "----granatum-${UUID.randomUUID()}"
        val inicio = "--$frontera\r\nContent-Disposition: form-data; name=\"ficheros\"; filename=\"enorme.pdf\"\r\n" +
            "Content-Type: application/pdf\r\n\r\n"
        val fin = "\r\n--$frontera--\r\n"
        val tamano = 60L * 1024 * 1024
        val longitud = inicio.length + tamano + fin.length

        java.net.Socket("localhost", puerto).use { socket ->
            socket.soTimeout = 30_000
            val salida = socket.getOutputStream()
            salida.write(
                ("POST /api/facturacion/facturas HTTP/1.1\r\nHost: localhost\r\n" +
                    "Authorization: Bearer ${token(Role.ADMIN)}\r\n" +
                    "Content-Type: multipart/form-data; boundary=$frontera\r\n" +
                    "Content-Length: $longitud\r\nConnection: close\r\n\r\n$inicio").toByteArray()
            )
            val envio = Thread {
                runCatching {
                    val bloque = ByteArray(64 * 1024)
                    var enviado = 0L
                    while (enviado < tamano) { salida.write(bloque); enviado += bloque.size }
                    salida.write(fin.toByteArray())
                }
            }.apply { isDaemon = true; start() }

            val respuesta = socket.getInputStream().bufferedReader().readText()
            envio.join(5_000)
            assertTrue(respuesta.startsWith("HTTP/1.1 413"), respuesta.take(200))
            assertTrue(respuesta.contains("PETICION_DEMASIADO_GRANDE"), respuesta.take(400))
        }
    }

    @Test
    fun `an unreadable JSON body answers VALIDACION`() {
        comprobar(
            http.post("/api/categorias", """{"nombre": "sin cerrar""", token(Role.ENCARGADO))
        )
    }
}
