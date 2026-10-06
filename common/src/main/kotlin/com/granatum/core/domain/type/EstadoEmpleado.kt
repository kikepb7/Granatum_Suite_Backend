package com.granatum.core.domain.type

/**
 * Whether a member of staff is currently employed.
 *
 * Lives in `common` because it is the vocabulary of the
 * [com.granatum.core.domain.contract.DirectorioEmpleados] contract: the module
 * that answers (`timetracking`) and the module that asks (`auth`) both need the
 * type, and neither may depend on the other.
 *
 * There is no `NO_EXISTE` case on purpose. "This person does not exist" is not
 * a state a person can be in, so the contract returns `null` for it; folding it
 * in here would let a caller handle it by accident instead of deliberately,
 * and FR-029c turns on exactly that distinction.
 */
enum class EstadoEmpleado {
    ACTIVO,
    INACTIVO
}
