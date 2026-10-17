package com.granatum.core.service

import com.granatum.core.domain.contract.MaterialStock
import com.granatum.core.domain.contract.StockMateriales
import com.granatum.core.domain.exception.EventoFinalizadoException
import com.granatum.core.domain.exception.EventoNotFoundException
import com.granatum.core.domain.exception.FechaPasadaException
import com.granatum.core.domain.model.DatosEvento
import com.granatum.core.domain.model.EventoModel
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.EstadoEvento
import com.granatum.core.infrastructure.database.entities.EventoEntity
import com.granatum.core.infrastructure.database.entities.EventoMaterialEntity
import com.granatum.core.infrastructure.database.mappers.toModel
import com.granatum.core.infrastructure.database.repositories.EventoMaterialRepository
import com.granatum.core.infrastructure.database.repositories.EventoRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Events and the materials they take (feature 011).
 *
 * Every change that moves stock runs in one transaction with the event's row locked: the event
 * line and the units taken from or returned to the inventory are saved together, or neither
 * (research.md D-003). Reading what the inventory says about each material - its location and
 * signed image - happens after the transaction, so signing images never holds a connection.
 */
@Service
class EventoService(
    private val eventoRepository: EventoRepository,
    private val lineaRepository: EventoMaterialRepository,
    private val stock: StockMateriales,
    private val transacciones: TransactionOperations,
    private val clock: Clock = Clock.systemUTC()
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Scheduled events soonest first; finished ones most recent first; without [estado], both. */
    fun listar(estado: EstadoEvento? = null): List<EventoModel> {
        val entidades = when (estado) {
            EstadoEvento.PROGRAMADO -> eventoRepository.findAllByEstadoOrderByFechaAscNombreAsc(EstadoEvento.PROGRAMADO)
            EstadoEvento.FINALIZADO -> eventoRepository.findAllByEstadoOrderByFechaDescNombreAsc(EstadoEvento.FINALIZADO)
            null -> eventoRepository.findAllByEstadoOrderByFechaAscNombreAsc(EstadoEvento.PROGRAMADO) +
                eventoRepository.findAllByEstadoOrderByFechaDescNombreAsc(EstadoEvento.FINALIZADO)
        }
        val lineas = lineaRepository.findAllByEventoIdIn(entidades.map { it.id }).groupBy { it.eventoId }
        return conInventario(entidades.map { it.toModel(lineas[it.id].orEmpty()) })
    }

    fun obtener(id: EntityId): EventoModel {
        val evento = eventoRepository.findById(id).orElseThrow { EventoNotFoundException(id) }
        return conInventario(evento.toModel(lineaRepository.findAllByEventoId(id)))
    }

    fun crear(usuarioId: EntityId, datos: DatosEvento): EventoModel {
        comprobarFecha(datos.fecha)
        val evento = eventoRepository.saveAndFlush(
            EventoEntity(
                nombre = datos.nombre.trim(),
                descripcion = datos.descripcion?.trim()?.ifEmpty { null },
                fecha = datos.fecha,
                creadoPor = usuarioId
            )
        )
        return evento.toModel(emptyList())
    }

    fun editar(id: EntityId, datos: DatosEvento): EventoModel = escribir(id) { evento, lineas ->
        comprobarFecha(datos.fecha)
        evento.nombre = datos.nombre.trim()
        evento.descripcion = datos.descripcion?.trim()?.ifEmpty { null }
        evento.fecha = datos.fecha
        lineas
    }

    /**
     * Sets how many units of a material the event takes, moving only the difference: more takes
     * them out of the inventory, fewer puts them back (research.md D-004). When there are not
     * enough units the inventory refuses and nothing changes (FR-005).
     */
    fun asignarMaterial(id: EntityId, materialId: EntityId, cantidad: Int, usuarioId: EntityId): EventoModel =
        escribir(id) { evento, lineas ->
            require(cantidad > 0) { "La cantidad debe ser positiva" }
            val linea = lineas.firstOrNull { it.materialId == materialId }
            val diferencia = cantidad - (linea?.cantidad ?: 0)
            when {
                diferencia > 0 -> {
                    val material = stock.retirar(materialId, diferencia, usuarioId, motivoReserva(evento))
                    if (linea == null) {
                        return@escribir lineas + lineaRepository.save(nuevaLinea(evento, material, cantidad))
                    }
                    linea.cantidad = cantidad
                }
                diferencia < 0 -> {
                    stock.devolver(materialId, -diferencia, usuarioId, "Devuelto del evento «${evento.nombre}»")
                    linea!!.cantidad = cantidad
                }
            }
            lineas
        }

    /** Puts every unit of the material back. Removing one the event does not take changes nothing. */
    fun quitarMaterial(id: EntityId, materialId: EntityId, usuarioId: EntityId): EventoModel =
        escribir(id) { evento, lineas ->
            val linea = lineas.firstOrNull { it.materialId == materialId } ?: return@escribir lineas
            stock.devolver(materialId, linea.cantidad, usuarioId, "Devuelto del evento «${evento.nombre}»")
            lineaRepository.delete(linea)
            lineas - linea
        }

    /**
     * Finishes the event and puts all its material back (FR-007, FR-008). [usuarioId] is who
     * pressed the button; `null` when the daily job does it, and then the history is written on
     * behalf of whoever created the event (research.md D-006). A finished event is returned as is.
     */
    fun finalizar(id: EntityId, usuarioId: EntityId?): EventoModel {
        val resultado = transacciones.execute {
            val evento = eventoRepository.findByIdForUpdate(id).orElseThrow { EventoNotFoundException(id) }
            val lineas = lineaRepository.findAllByEventoId(id)
            if (evento.estado == EstadoEvento.PROGRAMADO) {
                val motivo = if (usuarioId == null) {
                    "Devuelto automáticamente al finalizar el evento «${evento.nombre}»"
                } else {
                    "Devuelto al finalizar el evento «${evento.nombre}»"
                }
                lineas.forEach { stock.devolver(it.materialId, it.cantidad, usuarioId ?: evento.creadoPor, motivo) }
                evento.estado = EstadoEvento.FINALIZADO
                evento.finalizadoEn = Instant.now(clock)
                eventoRepository.saveAndFlush(evento)
            }
            evento.toModel(lineas)
        }!!
        return conInventario(resultado)
    }

    /** A scheduled event puts its material back first (FR-010); a finished one has nothing out. */
    fun borrar(id: EntityId, usuarioId: EntityId) {
        transacciones.executeWithoutResult {
            val evento = eventoRepository.findByIdForUpdate(id).orElseThrow { EventoNotFoundException(id) }
            val lineas = lineaRepository.findAllByEventoId(id)
            if (evento.estado == EstadoEvento.PROGRAMADO) {
                lineas.forEach { stock.devolver(it.materialId, it.cantidad, usuarioId, "Devuelto al borrar el evento «${evento.nombre}»") }
            }
            lineaRepository.deleteAll(lineas)
            eventoRepository.delete(evento)
        }
    }

    /** What can be taken to an event, for a role that has no access to the inventory (FR-012). */
    fun materialesDisponibles(): List<MaterialStock> =
        stock.disponibles().sortedWith(compareBy({ it.categoria.lowercase() }, { it.descripcion.lowercase() }))

    /**
     * Finishes every scheduled event of a day already gone (FR-007), each in its own transaction
     * so that a failure in one does not undo the others. Returns how many it finished.
     */
    fun finalizarVencidos(): Int {
        val ids = eventoRepository.idsConEstadoAntesDe(EstadoEvento.PROGRAMADO, hoy())
        return ids.count { id ->
            runCatching { finalizar(id, usuarioId = null) }
                .onFailure { log.warn("No se pudo finalizar un evento vencido: {}", it.javaClass.simpleName) }
                .isSuccess
        }
    }

    fun hoy(): LocalDate = LocalDate.now(clock.withZone(ZONA))

    /** Locks the event, refuses a finished one and runs [cambio] on it and its lines. */
    private fun escribir(
        id: EntityId,
        cambio: (EventoEntity, List<EventoMaterialEntity>) -> List<EventoMaterialEntity>
    ): EventoModel {
        val resultado = transacciones.execute {
            val evento = eventoRepository.findByIdForUpdate(id).orElseThrow { EventoNotFoundException(id) }
            if (evento.estado == EstadoEvento.FINALIZADO) throw EventoFinalizadoException(id)
            val lineas = cambio(evento, lineaRepository.findAllByEventoId(id))
            // The modification date says when the event last changed, its material included.
            evento.updatedAt = Instant.now(clock)
            eventoRepository.saveAndFlush(evento)
            lineaRepository.flush()
            evento.toModel(lineas)
        }!!
        return conInventario(resultado)
    }

    private fun comprobarFecha(fecha: LocalDate) {
        val hoy = hoy()
        if (fecha.isBefore(hoy)) throw FechaPasadaException(fecha, hoy)
    }

    private fun nuevaLinea(evento: EventoEntity, material: MaterialStock, cantidad: Int) = EventoMaterialEntity(
        eventoId = evento.id,
        materialId = material.id,
        cantidad = cantidad,
        descripcion = material.descripcion.take(DESCRIPCION_MAX),
        categoria = material.categoria.take(CATEGORIA_MAX)
    )

    private fun motivoReserva(evento: EventoEntity) =
        "Reservado para el evento «${evento.nombre}» del ${evento.fecha.format(FECHA)}"

    /** Adds where each material is and its image, as the inventory has them now. */
    private fun conInventario(evento: EventoModel): EventoModel = conInventario(listOf(evento)).single()

    private fun conInventario(eventos: List<EventoModel>): List<EventoModel> {
        val ids = eventos.flatMap { e -> e.materiales.map { it.materialId } }.toSet()
        if (ids.isEmpty()) return eventos
        val actuales = stock.resumen(ids)
        return eventos.map { evento ->
            evento.copy(
                materiales = evento.materiales.map { linea ->
                    actuales[linea.materialId]?.let { linea.copy(almacen = it.almacen, seccion = it.seccion, imagenUrl = it.imagenUrl) } ?: linea
                }
            )
        }
    }

    companion object {
        /** "Today" is the civil day in Spain, where the events happen. */
        val ZONA: ZoneId = ZoneId.of("Europe/Madrid")
        private val FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        private const val DESCRIPCION_MAX = 500
        private const val CATEGORIA_MAX = 100
    }
}
