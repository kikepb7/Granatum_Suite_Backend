package com.granatum.core.domain.exception

import com.granatum.core.domain.type.EntityId

/** The material is assigned to an event that has not finished (feature 011, FR-013). */
class MaterialEnUsoException(id: EntityId) :
    RuntimeException("El material $id está asignado a un evento programado; quítalo del evento o finalízalo antes de borrarlo")
