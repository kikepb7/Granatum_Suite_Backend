package com.granatum.core.domain.type

/**
 * The three warehouses. Provisional names (spec, Assumptions): renaming them
 * means changing this enum, the `chk_materiales_almacen` check (V26) and the
 * request validation (research.md D-006).
 */
enum class Almacen {
    A,
    B,
    C
}
