package com.granatum.core.infrastructure.contract

import com.granatum.core.domain.contract.MaterialUsage
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.EventStatus
import com.granatum.core.infrastructure.database.repositories.EventMaterialRepository
import org.springframework.stereotype.Component

/** The events side of [MaterialUsage] (feature 011): out at a scheduled event means in use. */
@Component
class MaterialUsageFromEvents(private val lines: EventMaterialRepository) : MaterialUsage {
    override fun isInUse(materialId: EntityId): Boolean = lines.usedInStatus(materialId, EventStatus.SCHEDULED)
}
