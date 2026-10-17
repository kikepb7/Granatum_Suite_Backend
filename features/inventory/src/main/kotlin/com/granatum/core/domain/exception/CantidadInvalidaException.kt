package com.granatum.core.domain.exception

import com.granatum.core.domain.type.EntityId

class CantidadInvalidaException(id: EntityId, cantidad: Int) : InvalidOperationException(
    "La cantidad ($cantidad) del material $id no puede ser negativa"
)
