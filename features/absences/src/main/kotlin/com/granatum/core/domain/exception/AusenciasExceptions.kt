package com.granatum.core.domain.exception

/*
 * Feature 007. Names unique across the product (SinColisionDeClasesIT): every
 * module shares the com.granatum.core package, and timetracking already has
 * EmpleadoNotFoundException and friends. No message carries a comment or a
 * reason - they reach clients and may reach logs (FR-021).
 */

class AusenciaNoEncontradaException(id: Any) :
    NotFoundException("No existe ninguna ausencia con el id $id")

class AusenciaSolapadaException :
    InvalidOperationException("Esa persona ya tiene una ausencia en alguno de esos días")

class SaldoVacacionesInsuficienteException(anio: Int, disponibles: Int) :
    InvalidOperationException("No quedan días suficientes de vacaciones en $anio (disponibles: $disponibles)")

class RangoAusenciaInvalidoException(detalle: String) :
    InvalidOperationException(detalle)

class DatosAusenciaInvalidosException(detalle: String) :
    InvalidOperationException(detalle)

class AusenciaNoModificableException(detalle: String) :
    InvalidOperationException(detalle)

class ResolucionPropiaAusenciaException :
    ForbiddenException("Una persona no resuelve ni registra sus propias ausencias")

class PersonaAusenciaNoEncontradaException(id: Any) :
    NotFoundException("No existe ninguna persona de la plantilla con el id $id")

class PersonaAusenciaInactivaException :
    InvalidOperationException("Esa persona no está en activo")
