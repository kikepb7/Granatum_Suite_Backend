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
sealed class InvoicingException(
    val status: HttpStatus,
    val code: String,
    override val message: String
) : RuntimeException(message)

class InvoiceNotFoundException(id: Any) :
    InvoicingException(HttpStatus.NOT_FOUND, "INVOICE_NOT_FOUND", "No existe la factura $id")

/** 404 when reading the company data, 409 when an operation needs it. */
class CompanyNotConfiguredException(onRead: Boolean = false) : InvoicingException(
    if (onRead) HttpStatus.NOT_FOUND else HttpStatus.CONFLICT,
    "COMPANY_NOT_CONFIGURED",
    "Faltan los datos de la empresa: sin su NIF no se sabe si una factura es emitida o recibida"
)

class InvalidNifException :
    InvoicingException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_NIF", "El NIF no tiene una letra o un digito de control valido")

class InconsistentInvoiceException(summary: String) :
    InvoicingException(HttpStatus.UNPROCESSABLE_ENTITY, "INCONSISTENT_INVOICE", "La factura no se puede confirmar: $summary")

class DuplicateInvoiceException :
    InvoicingException(HttpStatus.CONFLICT, "DUPLICATE_INVOICE", "Ya hay una factura confirmada con el mismo emisor, numero y fecha")

class QuarterClosedException(year: Int, quarter: Int) :
    InvoicingException(HttpStatus.CONFLICT, "QUARTER_CLOSED", "El trimestre $year-T$quarter esta cerrado; hay que reabrirlo antes")

class QuarterOpenException(year: Int, quarter: Int) :
    InvoicingException(HttpStatus.CONFLICT, "QUARTER_OPEN", "El trimestre $year-T$quarter no esta cerrado")

class QuarterHasPendingException(pending: Int) :
    InvoicingException(HttpStatus.CONFLICT, "QUARTER_HAS_PENDING", "Quedan $pending facturas del trimestre sin confirmar ni descartar")

class InvoiceStatusNotAllowedException(detail: String) :
    InvoicingException(HttpStatus.CONFLICT, "STATUS_NOT_ALLOWED", detail)

class StaleInvoiceVersionException :
    InvoicingException(HttpStatus.CONFLICT, "STALE_VERSION", "La factura ha cambiado desde que se leyo; vuelve a cargarla")

class RecognitionUnavailableException :
    InvoicingException(HttpStatus.CONFLICT, "RECOGNITION_UNAVAILABLE", "El reconocimiento automatico no esta configurado en este entorno")

class InvalidReportPeriodException(detail: String) :
    InvoicingException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PERIOD", detail)
