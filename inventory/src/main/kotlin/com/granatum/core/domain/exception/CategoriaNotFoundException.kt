package com.granatum.core.domain.exception

import com.granatum.core.domain.type.EntityId

class CategoriaNotFoundException(id: EntityId) : NotFoundException("Categoria $id not found")
