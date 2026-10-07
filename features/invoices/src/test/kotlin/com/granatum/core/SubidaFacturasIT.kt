package com.granatum.core

import com.granatum.core.domain.model.EstadoFactura
import com.granatum.core.domain.port.AlmacenDocumentos
import com.granatum.core.domain.port.ResultadoReconocimiento
import com.granatum.core.service.FacturaLecturas
import com.granatum.core.service.FicheroSubido
import com.granatum.core.service.SubidaFacturas
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * US1: uploading invoices and getting them recognised (FR-001 to FR-006,
 * FR-014, SC-006), with the double in place of Claude.
 *
 * ## The rule under test above all: never call Claude inside a transaction
 *
 * A call can take tens of seconds; holding a database connection meanwhile is
 * how features 002 and 003 exhausted the pool (research.md D-005). The double
 * records whether a transaction was active when it was called.
 *
 * Validated by mutation (2026-10-08): wrapping the call to the recogniser in
 * `ColaReconocimiento` with `transacciones.execute { … }` turned
 * `the recogniser is never called inside a transaction` red; restored.
 */
@Testcontainers
@SpringBootTest(classes = [InvoicesTestApplication::class])
@Import(ConReconocedorFalso::class)
class SubidaFacturasIT {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

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

    @Autowired lateinit var subida: SubidaFacturas
    @Autowired lateinit var falso: ReconocedorFalso
    @Autowired lateinit var almacen: AlmacenDocumentos
    @Autowired lateinit var lecturas: FacturaLecturas
    @Autowired lateinit var dataSource: DataSource

    private val admin = UUID.randomUUID()

    @BeforeEach
    fun reiniciar() = falso.reiniciar()

    private fun subir(vararg contenidos: ByteArray) =
        subida.subir(contenidos.map { FicheroSubido("factura.pdf", it) }, admin)

    /** Recognition runs in the background: wait until the invoice leaves the pending state. */
    private fun esperarEstado(id: UUID, estado: EstadoFactura) {
        val limite = System.nanoTime() + 10_000_000_000
        while (System.nanoTime() < limite) {
            if (lecturas.estado(id) == estado) return
            Thread.sleep(50)
        }
        throw AssertionError("la factura no llego a $estado; esta en ${lecturas.estado(id)}")
    }

    private fun esperarIntentos(id: UUID, n: Int) {
        val limite = System.nanoTime() + 10_000_000_000
        while (System.nanoTime() < limite && contar("factura_reconocimientos", id) < n) Thread.sleep(50)
    }

    private fun contar(tabla: String, facturaId: UUID): Int = dataSource.connection.use { c ->
        c.prepareStatement("SELECT count(*) FROM $tabla WHERE factura_id = ?").use { s ->
            s.setObject(1, facturaId); s.executeQuery().use { r -> r.next(); r.getInt(1) }
        }
    }

    @Test
    fun `several files give one result each, in order and without names`() {
        val r = subir(Muestras.pdf(), Muestras.png(), Muestras.texto(), ByteArray(0))
        assertEquals(listOf(1, 2, 3, 4), r.map { it.fichero })
        assertEquals(listOf("ACEPTADA", "ACEPTADA", "FORMATO_NO_ADMITIDO", "VACIO"), r.map { it.resultado })
        assertTrue(r[0].facturaId != null && r[1].facturaId != null && r[2].facturaId == null)
    }

    /** Finding I1: the 10 MB per file is the service's, so one big file does not sink the rest. */
    @Test
    fun `a file over 10 MB is refused alone`() {
        val r = subir(Muestras.pdf(), Muestras.pdfDemasiadoGrande(), Muestras.png())
        assertEquals(listOf("ACEPTADA", "DEMASIADO_GRANDE", "ACEPTADA"), r.map { it.resultado })
    }

    @Test
    fun `an unreadable PDF is refused at upload`() {
        assertEquals("PDF_NO_LEGIBLE", subir("%PDF-1.7 roto".toByteArray()).single().resultado)
    }

    @Test
    fun `the same file twice is a duplicate pointing at the first`() {
        val pdf = Muestras.pdf()
        val primero = subir(pdf).single()
        val segundo = subir(pdf).single()
        assertEquals("DUPLICADA", segundo.resultado)
        assertEquals(primero.facturaId, segundo.facturaId)
    }

    /** The database's unique index, not the service's check, is what holds under a race (D-015). */
    @Test
    fun `two simultaneous uploads of the same file leave a single invoice`() {
        val pdf = Muestras.pdf()
        val salida = CountDownLatch(1)
        val hilos = Executors.newFixedThreadPool(2)
        val futuros = (1..2).map { hilos.submit<String> { salida.await(); subir(pdf).single().resultado } }
        salida.countDown()
        val resultados = futuros.map { it.get(20, TimeUnit.SECONDS) }.sorted()
        hilos.shutdown()

        assertEquals(listOf("ACEPTADA", "DUPLICADA"), resultados)
        val huella = MessageDigest.getInstance("SHA-256").digest(pdf).joinToString("") { "%02x".format(it) }
        val filas = dataSource.connection.use { c ->
            c.prepareStatement("SELECT count(*) FROM facturas WHERE documento_sha256 = ?").use { s ->
                s.setString(1, huella); s.executeQuery().use { r -> r.next(); r.getInt(1) }
            }
        }
        assertEquals(1, filas)
    }

    @Test
    fun `the stored original is identical to the upload`() {
        val png = Muestras.png()
        val id = subir(png).single().facturaId!!
        val guardado = almacen.leer(id)
        assertContentEquals(png, guardado.contenido)
        assertEquals("image/png", guardado.mediaType)
    }

    @Test
    fun `a recognised invoice becomes a draft with what was proposed, two VAT lines included`() {
        val id = subir(Muestras.pdf()).single().facturaId!!
        esperarEstado(id, EstadoFactura.BORRADOR)

        val f = lecturas.factura(id)
        assertEquals("2026-0815", f.numero)
        assertEquals("A58818501", f.emisor?.nif)
        assertEquals(BigDecimal("176.00"), f.total)
        assertEquals(2, f.lineas.size)
        assertEquals(BigDecimal("21.00"), f.lineas[0].tipoIva)
        assertEquals(BigDecimal("5.00"), f.lineas[1].cuota)
        assertEquals(1, contar("factura_reconocimientos", id))
    }

    /** FR-004: what was not read stays empty, never invented. */
    @Test
    fun `a doubtful field stays empty`() {
        falso.guion = { ReconocedorFalso.reconocida(ReconocedorFalso.propuesta(numero = null, camposDudosos = listOf("numero"))) }
        val id = subir(Muestras.pdf()).single().facturaId!!
        esperarEstado(id, EstadoFactura.BORRADOR)
        assertNull(lecturas.factura(id).numero)
    }

    @Test
    fun `something that is not an invoice leaves a draft with no figures`() {
        falso.guion = {
            ReconocedorFalso.reconocida(
                ReconocedorFalso.propuesta(
                    esFactura = false, emisorNombre = null, emisorNif = null, destinatarioNombre = null,
                    destinatarioNif = null, numero = null, fechaEmision = null, concepto = null,
                    lineas = emptyList(), retenciones = null, total = null
                )
            )
        }
        val id = subir(Muestras.png()).single().facturaId!!
        esperarEstado(id, EstadoFactura.BORRADOR)
        val f = lecturas.factura(id)
        assertNull(f.total)
        assertTrue(f.lineas.isEmpty())
        assertEquals("NO_ES_FACTURA", lecturas.ultimoReconocimiento(id)?.resultado)
    }

    @Test
    fun `a failed recognition leaves the invoice pending, with the attempt recorded`() {
        falso.guion = { ResultadoReconocimiento.Fallida("ERROR_API", ReconocedorFalso.MODELO) }
        val id = subir(Muestras.pdf()).single().facturaId!!
        esperarIntentos(id, 1)

        assertEquals(EstadoFactura.PENDIENTE_RECONOCER, lecturas.estado(id))
        assertEquals("ERROR", lecturas.ultimoReconocimiento(id)?.resultado)
        assertEquals("ERROR_API", lecturas.ultimoReconocimiento(id)?.error)
    }

    @Test
    fun `the recogniser is never called inside a transaction`() {
        val ids = subir(Muestras.pdf(), Muestras.png()).map { it.facturaId!! }
        ids.forEach { esperarEstado(it, EstadoFactura.BORRADOR) }
        assertTrue(falso.llamadas.size >= 2)
        assertTrue(falso.llamadas.none { it.conTransaccion }, "a call held a database connection: ${falso.llamadas}")
    }
}
