package com.granatum.core.domain.exception

import com.granatum.core.domain.type.EntityId

class CategoryNotFoundException(id: EntityId) : NotFoundException("Categoria $id not found")
