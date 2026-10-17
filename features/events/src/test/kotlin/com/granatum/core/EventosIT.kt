package com.granatum.core

import com.granatum.core.domain.contract.UsoMateriales
import com.granatum.core.domain.model.DatosEvento
import com.granatum.core.domain.type.EstadoEvento
import com.granatum.core.infrastructure.database.entities.EventoEntity
import com.granatum.core.infrastructure.database.repositories.EventoMaterialRepository
import com.granatum.core.infrastructure.database.repositories.EventoRepository
import com.granatum.core.scheduling.FinalizacionEventosJob
import com.granatum.core.service.EventoService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Feature 011 against a real Postgres: V27 applies, the JPA mapping matches it, and an event's
 * whole life - material in, material out, finished by the job - leaves stock and rows right.
 */
@Testcontainers
@SpringBootTest(classes = [EventsTestApplication::class])
class EventosIT : BaseEventosIT() {

    @Autowired lateinit var sut: EventoService
    @Autowired lateinit var job: FinalizacionEventosJob
    @Autowired lateinit var usoMateriales: UsoMateriales
    @Autowired lateinit var eventoRepository: EventoRepository
    @Autowired lateinit var lineaRepository: EventoMaterialRepository
    @Autowired lateinit var jdbc: JdbcTemplate

    private val usuario = UUID.randomUUID()

    private fun nuevo(nombre: String = "Evento 1") =
        sut.crear(usuario, DatosEvento(nombre, "Boda de Juan y María", sut.hoy().plusDays(3)))

    @Test
    fun `When material is assigned and removed then the stock and the lines follow`() {
        val evento = nuevo()
        val material = stock.material(cantidad = 8)

        sut.asignarMaterial(evento.id, material, 5, usuario)
        assertEquals(expected = 3, actual = stock.cantidad(material))
        assertTrue(usoMateriales.enUso(material))

        val actual = sut.obtener(evento.id)
        assertEquals(expected = listOf(5), actual = actual.materiales.map { it.cantidad })
        assertEquals(expected = "Jarrones", actual = actual.materiales.single().categoria)

        sut.quitarMaterial(evento.id, material, usuario)
        assertEquals(expected = 8, actual = stock.cantidad(material))
        assertFalse(usoMateriales.enUso(material))
        assertTrue(lineaRepository.findAllByEventoId(evento.id).isEmpty())
    }

    @Test
    fun `When the job runs then past events finish, their material returns and nothing moves twice`() {
        val material = stock.material(cantidad = 10)
        val pasado = eventoRepository.saveAndFlush(
            EventoEntity(nombre = "Ayer", descripcion = null, fecha = sut.hoy().minusDays(1), creadoPor = usuario)
        )
        val futuro = nuevo("Mañana")
        sut.asignarMaterial(pasado.id, material, 4, usuario)
        sut.asignarMaterial(futuro.id, material, 3, usuario)
        assertEquals(expected = 3, actual = stock.cantidad(material))

        job.finalizarVencidos()
        job.finalizarVencidos()

        val actual = sut.obtener(pasado.id)
        assertEquals(expected = EstadoEvento.FINALIZADO, actual = actual.estado)
        assertNotNull(actual.finalizadoEn)
        assertEquals(expected = 7, actual = stock.cantidad(material))
        assertEquals(expected = EstadoEvento.PROGRAMADO, actual = sut.obtener(futuro.id).estado)
        assertTrue(usoMateriales.enUso(material), "still out at tomorrow's event")
    }

    @Test
    fun `When events are listed then scheduled come soonest first and finished ones apart`() {
        val tarde = sut.crear(usuario, DatosEvento("Tarde", null, sut.hoy().plusDays(30)))
        val pronto = sut.crear(usuario, DatosEvento("Pronto", null, sut.hoy().plusDays(1)))
        sut.finalizar(tarde.id, usuario)

        val programados = sut.listar(EstadoEvento.PROGRAMADO).map { it.id }
        val finalizados = sut.listar(EstadoEvento.FINALIZADO).map { it.id }

        assertTrue(pronto.id in programados && tarde.id !in programados)
        assertTrue(tarde.id in finalizados)
    }

    @Test
    fun `When an event is deleted then its lines go with it and the stock returns`() {
        val evento = nuevo()
        val material = stock.material(cantidad = 2)
        sut.asignarMaterial(evento.id, material, 2, usuario)

        sut.borrar(evento.id, usuario)

        assertEquals(expected = 2, actual = stock.cantidad(material))
        assertEquals(
            expected = 0,
            actual = jdbc.queryForObject("SELECT count(*) FROM evento_materiales WHERE evento_id = ?", Int::class.java, evento.id)
        )
    }

    @Test
    fun `When the schema is checked then the database refuses impossible rows`() {
        val evento = nuevo()

        val sinFinalizacion = runCatching {
            jdbc.update("UPDATE eventos SET estado = 'FINALIZADO' WHERE id = ?", evento.id)
        }
        val cantidadCero = runCatching {
            jdbc.update(
                "INSERT INTO evento_materiales (id, evento_id, material_id, cantidad, descripcion, categoria) VALUES (?, ?, ?, 0, 'x', 'y')",
                UUID.randomUUID(), evento.id, UUID.randomUUID()
            )
        }

        assertTrue(sinFinalizacion.isFailure, "FINALIZADO without finalizado_en")
        assertTrue(cantidadCero.isFailure, "a line of zero units")
    }
}
