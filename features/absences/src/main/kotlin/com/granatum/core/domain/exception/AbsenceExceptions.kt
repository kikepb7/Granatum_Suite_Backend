package com.granatum.core.domain.exception

/*
 * Feature 007. Names unique across the product (SinColisionDeClasesIT): every
 * module shares the com.granatum.core package, and timetracking already has
 * its own employee-not-found exception and friends. No message carries a
 * comment or a reason - they reach clients and may reach logs (FR-021).
 */

class AbsenceNotFoundException(id: Any) :
    NotFoundException("No existe ninguna ausencia con el id $id")

class AbsenceOverlapException :
    InvalidOperationException("Esa persona ya tiene una ausencia en alguno de esos días")

class InsufficientVacationBalanceException(year: Int, available: Int) :
    InvalidOperationException("No quedan días suficientes de vacaciones en $year (disponibles: $available)")

class InvalidAbsenceRangeException(detail: String) :
    InvalidOperationException(detail)

class InvalidAbsenceDataException(detail: String) :
    InvalidOperationException(detail)

class AbsenceNotModifiableException(detail: String) :
    InvalidOperationException(detail)

class OwnAbsenceResolutionException :
    ForbiddenException("Una persona no resuelve ni registra sus propias ausencias")

class AbsenceEmployeeNotFoundException(id: Any) :
    NotFoundException("No existe ninguna persona de la plantilla con el id $id")

class AbsenceEmployeeInactiveException :
    InvalidOperationException("Esa persona no está en activo")
