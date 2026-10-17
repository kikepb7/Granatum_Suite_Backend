package com.granatum.core.domain.type

/**
 * State of a correction request. [APPROVED] and [REJECTED] are both terminal:
 * resolving twice is rejected.
 */
enum class CorrectionStatus {
    PENDING,
    APPROVED,
    REJECTED
}
