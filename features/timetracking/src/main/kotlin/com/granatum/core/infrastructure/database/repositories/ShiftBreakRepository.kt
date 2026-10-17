package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.infrastructure.database.entities.ShiftBreakEntity
import org.springframework.data.repository.Repository
import java.util.UUID

/** No mutation beyond `save`; see [EmployeeRepository] for why. */
interface ShiftBreakRepository : Repository<ShiftBreakEntity, UUID> {
    fun save(shiftBreak: ShiftBreakEntity): ShiftBreakEntity
    fun findByShiftIdAndEndedAtIsNull(shiftId: UUID): ShiftBreakEntity?
}
