package com.granatum.core.domain.exception

import com.granatum.core.domain.type.EntityId

class InvalidMaterialQuantityException(id: EntityId, quantity: Int) : InvalidOperationException(
    "La cantidad ($quantity) del material $id no puede ser negativa"
)
