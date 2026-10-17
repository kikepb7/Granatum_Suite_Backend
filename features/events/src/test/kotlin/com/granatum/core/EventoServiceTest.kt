package com.granatum.core

import com.granatum.core.domain.contract.StockInsuficienteException
import com.granatum.core.domain.exception.EventoFinalizadoException
import com.granatum.core.domain.exception.FechaPasadaException
import com.granatum.core.domain.model.DatosEvento
import com.granatum.core.domain.model.EventoMaterialModel
import com.granatum.core.domain.type.EstadoEvento
import com.granatum.core.infrastructure.database.entities.EventoEntity
import com.granatum.core.infrastructure.database.entities.EventoMaterialEntity
import com.granatum.core.infrastructure.database.repositories.EventoMaterialRepository
import com.granatum.core.infrastructure.database.repositories.EventoRepository
import com.granatum.core.service.EventoService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Feature 011: the rules of [EventoService], without a database (principle V). */
class EventoServiceTest {

    private val eventoRepository = mockk<EventoRepository>()
    private val lineaRepository = mockk<EventoMaterialRepository>(relaxed = true)
    private val stock = StockMaterialesDoble()

    /** 2027-06-13 10:00 in Madrid. */
    private val clock = Clock.fixed(Instant.parse("2027-06-13T08:00:00Z"), ZoneOffset.UTC)
    private val hoy = LocalDate.of(2027, 6, 13)
    private val usuario = UUID.randomUUID()

    private val sut = EventoService(eventoRepository, lineaRepository, stock, TransactionOperations.withoutTransaction(), clock)

    private val lineas = mutableListOf<EventoMaterialEntity>()

    private fun evento(estado: EstadoEvento = EstadoEvento.PROGRAMADO, fecha: LocalDate = hoy) =
        EventoEntity(nombre = "Evento 1", descripcion = "Boda de Juan y María", fecha = fecha, estado = estado, creadoPor = usuario)
            .also { e ->
                every { eventoRepository.findByIdForUpdate(e.id) } returns Optional.of(e)
                every { eventoRepository.findById(e.id) } returns Optional.of(e)
                every { eventoRepository.saveAndFlush(e) } returns e
                every { lineaRepository.findAllByEventoId(e.id) } answers { lineas.toList() }
                every { lineaRepository.save(any()) } answers { firstArg<EventoMaterialEntity>().also { lineas += it } }
                every { lineaRepository.delete(any()) } answers { lineas -= firstArg<EventoMaterialEntity>() }
            }

    @Test
    fun `When an event is created for today then it is scheduled with the trimmed data`() {
        val guardado = slot<EventoEntity>()
        every { eventoRepository.saveAndFlush(capture(guardado)) } answers { guardado.captured }

        val actual = sut.crear(usuario, DatosEvento("  Evento 1 ", "  Boda de Juan y María ", hoy))

        assertEquals(expected = "Evento 1" to "Boda de Juan y María", actual = actual.nombre to actual.descripcion)
        assertEquals(expected = EstadoEvento.PROGRAMADO, actual = actual.estado)
        assertEquals(expected = usuario, actual = actual.creadoPor)
    }

    @Test
    fun `When the date has already gone then the event is not created`() {
        assertFailsWith<FechaPasadaException> { sut.crear(usuario, DatosEvento("Evento 1", null, hoy.minusDays(1))) }

        verify(exactly = 0) { eventoRepository.saveAndFlush(any()) }
    }

    @Test
    fun `When material is assigned then it is taken out of the inventory with the event in the reason`() {
        val evento = evento()
        val material = stock.material(cantidad = 8)
        val expected = listOf(EventoMaterialModel(material, "Jarrones", "Jarrón cilíndrico", 5, "A", 2, null))

        val actual = sut.asignarMaterial(evento.id, material, 5, usuario)

        assertEquals(expected = expected, actual = actual.materiales)
        assertEquals(expected = 3, actual = stock.cantidad(material))
        assertEquals(expected = "Reservado para el evento «Evento 1» del 13/06/2027", actual = stock.retiradas.single().motivo)
    }

    @Test
    fun `When the quantity changes then only the difference moves`() {
        val evento = evento()
        val material = stock.material(cantidad = 8)
        sut.asignarMaterial(evento.id, material, 5, usuario)

        sut.asignarMaterial(evento.id, material, 7, usuario)
        assertEquals(expected = 1, actual = stock.cantidad(material))

        val actual = sut.asignarMaterial(evento.id, material, 2, usuario)
        assertEquals(expected = 6, actual = stock.cantidad(material))
        assertEquals(expected = 2, actual = actual.materiales.single().cantidad)
        assertEquals(expected = listOf(5, 2), actual = stock.retiradas.map { it.cantidad })
        assertEquals(expected = listOf(5), actual = stock.devoluciones.map { it.cantidad })
    }

    @Test
    fun `When there is not enough stock then nothing is assigned`() {
        val evento = evento()
        val material = stock.material(cantidad = 3)

        val actual = assertFailsWith<StockInsuficienteException> { sut.asignarMaterial(evento.id, material, 4, usuario) }

        assertEquals(expected = 3, actual = actual.disponible)
        assertTrue(lineas.isEmpty())
        assertEquals(expected = 3, actual = stock.cantidad(material))
    }

    @Test
    fun `When a material is removed then all its units go back`() {
        val evento = evento()
        val material = stock.material(cantidad = 8)
        sut.asignarMaterial(evento.id, material, 5, usuario)

        val actual = sut.quitarMaterial(evento.id, material, usuario)

        assertEquals(expected = emptyList(), actual = actual.materiales)
        assertEquals(expected = 8, actual = stock.cantidad(material))
    }

    @Test
    fun `When an event is finished by hand then its material goes back on behalf of who pressed`() {
        val evento = evento()
        val material = stock.material(cantidad = 8)
        sut.asignarMaterial(evento.id, material, 5, usuario)
        val quien = UUID.randomUUID()

        val actual = sut.finalizar(evento.id, quien)

        assertEquals(expected = EstadoEvento.FINALIZADO, actual = actual.estado)
        assertEquals(expected = 8, actual = stock.cantidad(material))
        assertEquals(expected = quien, actual = stock.devoluciones.single().usuarioId)
        assertEquals(expected = 5, actual = actual.materiales.single().cantidad, message = "a finished event keeps what it took")
    }

    @Test
    fun `When the job finishes an event then the history is written for its creator and says so`() {
        val evento = evento(fecha = hoy.minusDays(1))
        val material = stock.material(cantidad = 8)
        sut.asignarMaterial(evento.id, material, 5, usuario)
        every { eventoRepository.idsConEstadoAntesDe(EstadoEvento.PROGRAMADO, hoy) } returns listOf(evento.id)

        val actual = sut.finalizarVencidos()

        assertEquals(expected = 1, actual = actual)
        val devolucion = stock.devoluciones.single()
        assertEquals(expected = usuario, actual = devolucion.usuarioId)
        assertEquals(expected = "Devuelto automáticamente al finalizar el evento «Evento 1»", actual = devolucion.motivo)
    }

    @Test
    fun `When a finished event is finished again then nothing moves`() {
        val evento = evento(estado = EstadoEvento.FINALIZADO)

        sut.finalizar(evento.id, usuario)

        assertTrue(stock.devoluciones.isEmpty())
    }

    @Test
    fun `When a finished event is edited or given material then it is refused`() {
        val evento = evento(estado = EstadoEvento.FINALIZADO)
        val material = stock.material(cantidad = 8)

        assertFailsWith<EventoFinalizadoException> { sut.editar(evento.id, DatosEvento("Otro", null, hoy)) }
        assertFailsWith<EventoFinalizadoException> { sut.asignarMaterial(evento.id, material, 1, usuario) }
        assertEquals(expected = 8, actual = stock.cantidad(material))
    }

    @Test
    fun `When a scheduled event is deleted then its material goes back`() {
        val evento = evento()
        val material = stock.material(cantidad = 8)
        sut.asignarMaterial(evento.id, material, 5, usuario)
        every { eventoRepository.delete(evento) } returns Unit

        sut.borrar(evento.id, usuario)

        assertEquals(expected = 8, actual = stock.cantidad(material))
        verify { eventoRepository.delete(evento) }
    }

    @Test
    fun `When a returned material no longer exists then finishing still succeeds`() {
        val evento = evento()
        val material = stock.material(cantidad = 8)
        sut.asignarMaterial(evento.id, material, 5, usuario)
        stock.borrar(material)

        val actual = sut.finalizar(evento.id, usuario)

        assertEquals(expected = EstadoEvento.FINALIZADO, actual = actual.estado)
        assertEquals(expected = null, actual = actual.materiales.single().almacen)
    }
}
