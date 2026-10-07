package com.granatum.core.domain.exception

/**
 * Business exceptions of the timetracking module, one per error code in
 * `contracts/README.md`.
 *
 * None of these messages may carry a document number or a location: error
 * bodies reach clients and logs, and principle VI forbids personal data in
 * either. Messages reference the field, never its value.
 */

// --- Not found -------------------------------------------------------------

class FichajeNotFoundException(id: Any) :
    NotFoundException("No existe el fichaje $id")

class EmpleadoNotFoundException(id: Any) :
    NotFoundException("No existe el empleado $id")

class SolicitudNotFoundException(id: Any) :
    NotFoundException("No existe la solicitud de correccion $id")

// --- Conflicts with the current state --------------------------------------

class FichajeYaEnCursoException :
    InvalidOperationException("Ya hay una jornada en curso para este empleado")

class PausaYaAbiertaException :
    InvalidOperationException("Ya hay una pausa sin cerrar en este fichaje")

class PausaNoAbiertaException :
    InvalidOperationException("No hay ninguna pausa abierta en este fichaje")

class PausaAbiertaAlCerrarException :
    InvalidOperationException("Hay una pausa sin terminar: ciérrala antes de fichar la salida")

class FichajeNoEnCursoException :
    InvalidOperationException("La operacion requiere un fichaje en curso")

class FichajeNoFinalizadoException :
    InvalidOperationException("Solo se pueden corregir fichajes ya finalizados")

class FichajeInmutableException :
    InvalidOperationException(
        "Un fichaje finalizado solo se modifica mediante una correccion aprobada"
    )

class SolicitudYaResueltaException :
    InvalidOperationException("La solicitud ya estaba resuelta")

class EmpleadoInactivoException :
    InvalidOperationException("El empleado esta inactivo y no puede fichar")

class DocumentoDuplicadoException :
    InvalidOperationException("Ya existe un empleado con ese documento de identidad")

class ClientEventIdReutilizadoException :
    InvalidOperationException(
        "El clientEventId ya se uso para otra operacion distinta"
    )

// --- Semantically invalid input --------------------------------------------

class ValoresIncoherentesException(detalle: String) :
    InvalidOperationException("Valores incoherentes: $detalle")

class UbicacionNoCorregibleException :
    InvalidOperationException("La ubicacion de un fichaje no es corregible")

/**
 * Deliberately its own type rather than a generic validation error: the mobile
 * app needs to tell the difference so it can explain that the device clock is
 * off, instead of showing a generic rejection the person cannot act on.
 */
class DesviacionRelojException(detalle: String) :
    InvalidOperationException("Desviacion de reloj: $detalle")

// --- Export (feature 003) --------------------------------------------------

/**
 * Every export slot is taken (D-002). Not a business rule being broken but a
 * temporary limit, so it maps to 503 with `Retry-After` rather than to a 4xx:
 * the same request will succeed a moment later.
 */
class ExportacionSaturadaException :
    RuntimeException("Hay demasiadas exportaciones en curso; vuelve a intentarlo en unos segundos")

/** The file sent for verification exceeds the configured limit (D-007). */
class FicheroDemasiadoGrandeException(maxBytes: Long) :
    RuntimeException("El fichero supera el tamaño máximo de $maxBytes bytes")
