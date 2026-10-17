package com.granatum.core.domain.type

/**
 * The three warehouses. Provisional names (spec, Assumptions): renaming them
 * means changing this enum, the `chk_materials_warehouse` check (V28) and the
 * request validation (research.md D-006).
 */
enum class Warehouse {
    A,
    B,
    C
}
