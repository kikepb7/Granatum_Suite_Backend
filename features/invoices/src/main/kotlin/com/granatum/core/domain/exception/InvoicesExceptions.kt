package com.granatum.core.domain.exception

import org.springframework.http.HttpStatus

/**
 * Business exceptions of the invoicing module, one per error code of
 * `specs/004-invoices/contracts/README.md`. Each carries its HTTP status and
 * code, so [com.granatum.core.api.exception_handling.InvoicesExceptionHandler]
 * maps them all in one place.
 *
 * No message may quote a name, a tax id or an amount of an invoice: error
 * bodies reach clients and logs alike (principle VI, FR-027).
 *
 * Class names are unique across modules (all share `com.granatum.core`); the
 * collision check in `app` covers this module too.
 */
sealed class FacturacionException(
    val estado: HttpStatus,
    val codigo: String,
    override val message: String
) : RuntimeException(message)

class FacturaNoEncontradaException(id: Any) :
    FacturacionException(HttpStatus.NOT_FOUND, "FACTURA_NOT_FOUND", "No existe la factura $id")

/** 404 when reading the company data, 409 when an operation needs it. */
class EmpresaSinConfigurarException(alConsultar: Boolean = false) : FacturacionException(
    if (alConsultar) HttpStatus.NOT_FOUND else HttpStatus.CONFLICT,
    "EMPRESA_SIN_CONFIGURAR",
    "Faltan los datos de la empresa: sin su NIF no se sabe si una factura es emitida o recibida"
)

class NifInvalidoException :
    FacturacionException(HttpStatus.UNPROCESSABLE_ENTITY, "NIF_INVALIDO", "El NIF no tiene una letra o un digito de control valido")

class FacturaIncoherenteException(resumen: String) :
    FacturacionException(HttpStatus.UNPROCESSABLE_ENTITY, "FACTURA_INCOHERENTE", "La factura no se puede confirmar: $resumen")

class FacturaDuplicadaException :
    FacturacionException(HttpStatus.CONFLICT, "FACTURA_DUPLICADA", "Ya hay una factura confirmada con el mismo emisor, numero y fecha")

class TrimestreCerradoException(anio: Int, trimestre: Int) :
    FacturacionException(HttpStatus.CONFLICT, "TRIMESTRE_CERRADO", "El trimestre $anio-T$trimestre esta cerrado; hay que reabrirlo antes")

class TrimestreAbiertoException(anio: Int, trimestre: Int) :
    FacturacionException(HttpStatus.CONFLICT, "TRIMESTRE_ABIERTO", "El trimestre $anio-T$trimestre no esta cerrado")

class TrimestreConPendientesException(pendientes: Int) :
    FacturacionException(HttpStatus.CONFLICT, "TRIMESTRE_CON_PENDIENTES", "Quedan $pendientes facturas del trimestre sin confirmar ni descartar")

class EstadoFacturaNoPermitidoException(detalle: String) :
    FacturacionException(HttpStatus.CONFLICT, "ESTADO_NO_PERMITIDO", detalle)

class VersionFacturaDesactualizadaException :
    FacturacionException(HttpStatus.CONFLICT, "VERSION_DESACTUALIZADA", "La factura ha cambiado desde que se leyo; vuelve a cargarla")

class ReconocimientoNoDisponibleException :
    FacturacionException(HttpStatus.CONFLICT, "RECONOCIMIENTO_NO_DISPONIBLE", "El reconocimiento automatico no esta configurado en este entorno")

class PeriodoInvalidoException(detalle: String) :
    FacturacionException(HttpStatus.UNPROCESSABLE_ENTITY, "PERIODO_INVALIDO", detalle)
