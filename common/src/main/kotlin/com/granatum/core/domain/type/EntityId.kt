package com.granatum.core.domain.type

import java.util.UUID

/**
 * Typed alias used across the codebase instead of raw [UUID] so identifiers
 * stay self-documenting (e.g. `MaterialId`, `EmployeeId`) without extra boxing.
 * Add one `typealias XyzId = EntityId` per aggregate as the domain grows.
 */
typealias EntityId = UUID
