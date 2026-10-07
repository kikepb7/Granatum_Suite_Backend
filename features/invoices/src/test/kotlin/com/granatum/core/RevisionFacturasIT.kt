package com.granatum.core

import com.granatum.core.domain.exception.EmpresaSinConfigurarException
import com.granatum.core.domain.exception.EstadoFacturaNoPermitidoException
import com.granatum.core.domain.exception.FacturaIncoherenteException
import com.granatum.core.domain.exception.VersionFacturaDesactualizadaException
import com.granatum.core.domain.model.EstadoFactura
import com.granatum.core.domain.model.LineaIva
import com.granatum.core.domain.model.TipoFactura
import com.granatum.core.domain.port.AlmacenDocumentos
import com.granatum.core.domain.port.ResultadoReconocimiento
import com.granatum.core.service.EmpresaService
import com.granatum.core.service.FacturaLecturas
import com.granatum.core.service.FicheroSubido
import com.granatum.core.service.RevisionFacturas
import com.granatum.core.service.SubidaFacturas
import com.granatum.core.service.ValoresFactura
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
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * US2: reviewing, correcting, confirming and discarding (FR-009 to FR-017).
 */
@Testcontainers
@SpringBootTest(classes = [InvoicesTestApplication::class])
@Import(ConReconocedorFalso::class)
class RevisionFacturasIT {

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

    @Autowired lateinit var revision: RevisionFacturas
    @Autowired lateinit var subida: SubidaFacturas
    @Autowired lateinit var lecturas: FacturaLecturas
    @Autowired lateinit var empresa: EmpresaService
    @Autowired lateinit var almacen: AlmacenDocumentos
    @Autowired lateinit var falso: ReconocedorFalso
    @Autowired lateinit var dataSource: DataSource

    private val e by lazy { EscenarioFacturas(subida, lecturas, falso, empresa, dataSource) }

    @BeforeEach
    fun preparar() {
        falso.reiniciar()
        e.configurarEmpresa()
    }

    private fun valores(id: UUID) = ValoresFactura.de(lecturas.factura(id))
    private fun d(v: String) = BigDecimal(v)

    @Test
    fun `a draft is corrected and stays a draft`() {
        val id = e.borrador()
        val f = revision.corregir(id, valores(id).copy(concepto = "Tulipanes"), lecturas.factura(id).version, e.admin)
        assertEquals("Tulipanes", f.concepto)
        assertEquals(EstadoFactura.BORRADOR, f.estado)
    }

    /** FR-006 (moved here from ModoManualIT): a pending invoice filled in by hand becomes a draft. */
    @Test
    fun `a pending invoice filled in by hand becomes a draft`() {
        falso.guion = { ResultadoReconocimiento.Fallida("ERROR_API", ReconocedorFalso.MODELO) }
        val id = subida.subir(listOf(FicheroSubido(null, Muestras.pdf())), e.admin).single().facturaId!!
        Thread.sleep(300)
        assertEquals(EstadoFactura.PENDIENTE_RECONOCER, lecturas.estado(id))

        val aMano = ValoresFactura(
            tipo = null, emisorNombre = "Proveedor", emisorNif = "A58818501", destinatarioNombre = null,
            destinatarioNif = "B12345674", numero = "M-${UUID.randomUUID().toString().take(6)}",
            fechaEmision = java.time.LocalDate.parse("2026-10-03"), concepto = "A mano", moneda = "EUR",
            lineas = listOf(LineaIva(d("21.00"), d("10.00"), d("2.10"))), retenciones = d("0.00"),
            total = d("12.10"), rectificativa = false
        )
        val f = revision.corregir(id, aMano, lecturas.factura(id).version, e.admin)
        assertEquals(EstadoFactura.BORRADOR, f.estado)
        assertEquals(EstadoFactura.CONFIRMADA, revision.confirmar(id, f.version, e.admin).estado)
    }

    /** FR-011: blocking warnings stop the confirmation. */
    @Test
    fun `an invoice that does not add up cannot be confirmed`() {
        val id = e.borrador(ReconocedorFalso.propuesta(numero = "N-${UUID.randomUUID().toString().take(6)}", total = "175.00"))
        assertFailsWith<FacturaIncoherenteException> { revision.confirmar(id, lecturas.factura(id).version, e.admin) }
        assertEquals(EstadoFactura.BORRADOR, lecturas.estado(id))
    }

    @Test
    fun `a correct one is confirmed, its type deduced from the company's tax id`() {
        val id = e.borrador()
        val f = revision.confirmar(id, lecturas.factura(id).version, e.admin)
        assertEquals(EstadoFactura.CONFIRMADA, f.estado)
        assertEquals(TipoFactura.RECIBIDA, f.tipo, "the company is the recipient")
        assertEquals(e.admin.toString(), e.texto("SELECT confirmada_por FROM facturas WHERE id = ?", id))
    }

    @Test
    fun `without the company's data nothing can be confirmed`() {
        val id = e.borrador()
        e.ejecutar("DELETE FROM empresa")
        try {
            assertFailsWith<EmpresaSinConfigurarException> { revision.confirmar(id, lecturas.factura(id).version, e.admin) }
        } finally {
            e.configurarEmpresa()
        }
    }

    @Test
    fun `a stale version is refused`() {
        val id = e.borrador()
        val vieja = lecturas.factura(id).version
        revision.corregir(id, valores(id).copy(concepto = "Primero"), vieja, e.admin)
        assertFailsWith<VersionFacturaDesactualizadaException> {
            revision.corregir(id, valores(id).copy(concepto = "Segundo"), vieja, e.admin)
        }
    }

    /** FR-015, FR-017 (finding C4). */
    @Test
    fun `discarding keeps the invoice and its original, and records who and when`() {
        val id = e.borrador()
        revision.descartar(id, lecturas.factura(id).version, e.admin)
        assertEquals(EstadoFactura.DESCARTADA, lecturas.estado(id))
        assertTrue(almacen.leer(id).contenido.isNotEmpty())
        assertEquals(e.admin.toString(), e.texto("SELECT descartada_por FROM facturas WHERE id = ?", id))
        assertNotNull(e.texto("SELECT descartada_en FROM facturas WHERE id = ?", id))
        assertFailsWith<EstadoFacturaNoPermitidoException> { revision.confirmar(id, lecturas.factura(id).version, e.admin) }
    }

    /** FR-016, FR-017: a confirmed invoice keeps its history, and never becomes incomplete. */
    @Test
    fun `correcting a confirmed invoice records the previous values and refuses an incomplete result`() {
        val id = e.borrador()
        val confirmada = revision.confirmar(id, lecturas.factura(id).version, e.admin)

        assertFailsWith<FacturaIncoherenteException> {
            revision.corregir(id, valores(id).copy(numero = null), confirmada.version, e.admin)
        }
        val corregida = revision.corregir(id, valores(id).copy(concepto = "Rosas blancas"), confirmada.version, e.admin)

        assertEquals("Rosas blancas", corregida.concepto)
        assertEquals(EstadoFactura.CONFIRMADA, corregida.estado)
        assertEquals("CORRECCION", e.texto("SELECT accion FROM factura_cambios WHERE factura_id = ?", id))
        val anteriores = e.texto("SELECT valores_anteriores::text FROM factura_cambios WHERE factura_id = ?", id)!!
        assertTrue(anteriores.contains("Rosas rojas") && anteriores.contains("176.00") && anteriores.contains("2026-10-02"), anteriores)
    }

    /** FR-008 (finding C3). */
    @Test
    fun `reclassifying a confirmed invoice is recorded as such`() {
        val id = e.borrador()
        val confirmada = revision.confirmar(id, lecturas.factura(id).version, e.admin)
        revision.corregir(id, valores(id).copy(tipo = TipoFactura.EMITIDA), confirmada.version, e.admin)
        assertEquals("RECLASIFICACION", e.texto("SELECT accion FROM factura_cambios WHERE factura_id = ?", id))
    }

    @Test
    fun `discarding a confirmed invoice is recorded`() {
        val id = e.borrador()
        val confirmada = revision.confirmar(id, lecturas.factura(id).version, e.admin)
        revision.descartar(id, confirmada.version, e.admin)
        assertEquals("DESCARTE", e.texto("SELECT accion FROM factura_cambios WHERE factura_id = ?", id))
    }

    /** FR-005: the proposal stays as it was, whatever the reviewer changes. */
    @Test
    fun `the original proposal survives a correction`() {
        val numero = "P-${UUID.randomUUID().toString().take(6)}"
        val id = e.borrador(ReconocedorFalso.propuesta(numero = numero))
        revision.corregir(id, valores(id).copy(numero = "OTRO-1"), lecturas.factura(id).version, e.admin)
        assertTrue(e.texto("SELECT propuesta::text FROM factura_reconocimientos WHERE factura_id = ?", id)!!.contains(numero))
    }
}
