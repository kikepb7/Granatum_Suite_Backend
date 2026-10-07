package com.granatum.core.domain.exception

import com.granatum.core.domain.type.EntityId

class CantidadInvalidaException(id: EntityId, cantidadDisponible: Int, cantidadTotal: Int) : InvalidOperationException(
    "La cantidad disponible ($cantidadDisponible) del material $id no puede ser negativa ni superar la cantidad total ($cantidadTotal)"
)
