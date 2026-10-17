package com.granatum.core.domain.exception

import com.granatum.core.domain.type.EntityId
import java.time.LocalDate

class EventoNotFoundException(id: EntityId) : NotFoundException("Evento $id not found")

/** A finished event is kept as it was (feature 011, FR-009). */
class EventoFinalizadoException(id: EntityId) :
    RuntimeException("El evento $id ya ha finalizado y no se puede modificar")

/** An event cannot be set on a day that has already gone (feature 011, FR-002). */
class FechaPasadaException(fecha: LocalDate, hoy: LocalDate) :
    RuntimeException("La fecha del evento ($fecha) no puede ser anterior a hoy ($hoy)")
