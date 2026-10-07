package com.granatum.core.domain.exception

import com.granatum.core.domain.type.EntityId

class MaterialNotFoundException(id: EntityId) : NotFoundException("Material $id not found")
