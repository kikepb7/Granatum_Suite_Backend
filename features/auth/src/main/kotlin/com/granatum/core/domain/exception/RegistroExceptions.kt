package com.granatum.core.domain.exception

/*
 * Feature 005 (sign-up). None of these messages carries the email, name,
 * document or code that caused it: they reach clients and may reach logs
 * (principle VI).
 */

/**
 * Wrong bootstrap code, none configured, or an ADMIN already exists. The three
 * causes answer the same on purpose: telling them apart would let anyone learn
 * whether an installation already has its ADMIN (research.md D-002).
 */
class CodigoArranqueInvalidoException :
    ForbiddenException("El código de arranque no es válido")

/** Too many pending requests at once (research.md D-004). */
class RegistroNoDisponibleException :
    InvalidOperationException("No se admiten más solicitudes de registro por ahora. Inténtalo más tarde")

class DocumentoInvalidoException :
    InvalidOperationException("El documento de identidad no es un DNI ni un NIE válido")

class SolicitudNoEncontradaException(id: Any) :
    NotFoundException("No existe ninguna solicitud de registro con el id $id")

class SolicitudNoPendienteException :
    InvalidOperationException("La solicitud de registro ya no está pendiente")

class CodigoIncorrectoException :
    InvalidOperationException("El código de verificación no coincide")

class DatosFichaRequeridosException :
    InvalidOperationException(
        "No hay ficha de personal con ese documento: indica puesto, tipo de contrato y fecha de alta para crearla"
    )
