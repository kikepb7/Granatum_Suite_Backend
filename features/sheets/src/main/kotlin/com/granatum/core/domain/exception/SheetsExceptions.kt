package com.granatum.core.domain.exception

import com.granatum.core.domain.port.SheetsFailureReason
import org.springframework.http.HttpStatus
import java.util.UUID

/**
 * Business exceptions of the sheets module, one per error code of
 * `specs/012-google-sheets-sync/contracts/README.md`. Each carries its HTTP
 * status, code and any extra fields of the body, so
 * [com.granatum.core.api.exception_handling.SheetsExceptionHandler] maps them
 * all in one place. Messages are Spanish and never quote credentials or sheet
 * content (principle VI, FR-008).
 *
 * Class names are unique across modules (all share `com.granatum.core`).
 */
sealed class SheetsApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
    val extra: Map<String, String> = emptyMap()
) : RuntimeException(message)

/** Which of the two spreadsheets a settings error is about, as the API names it. */
enum class SpreadsheetRole(val apiName: String, val spanish: String) {
    SHIFTS("shifts", "fichajes"),
    INVOICES("invoices", "recibos")
}

class InvalidSpreadsheetException(role: SpreadsheetRole) : SheetsApiException(
    HttpStatus.BAD_REQUEST,
    "VALIDATION",
    "El enlace o identificador de la hoja de ${role.spanish} no es válido: pega el enlace de la hoja de Google o su identificador"
)

class SameSpreadsheetException : SheetsApiException(
    HttpStatus.CONFLICT,
    "SAME_SPREADSHEET",
    "Las hojas de fichajes y de recibos deben ser distintas: las dos usan una pestaña por mes con el mismo nombre"
)

class SpreadsheetNotAccessibleException(role: SpreadsheetRole, reason: SheetsFailureReason, serviceAccountEmail: String) :
    SheetsApiException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "SPREADSHEET_NOT_ACCESSIBLE",
        "No se puede escribir en la hoja de ${role.spanish}: ${reason.message.replaceFirstChar { it.lowercase() }}. " +
            "Compártela con $serviceAccountEmail como editora.",
        mapOf("spreadsheet" to role.apiName, "reason" to reason.name)
    )

class SheetsUnreachableException : SheetsApiException(
    HttpStatus.BAD_GATEWAY,
    "SHEETS_UNREACHABLE",
    "Google no responde en este momento; no se ha guardado nada. Inténtalo de nuevo en unos minutos."
)

class SheetTaskNotFoundException(id: UUID) :
    SheetsApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "No existe la tarea $id")

class SheetTaskNotFailedException : SheetsApiException(
    HttpStatus.CONFLICT,
    "TASK_NOT_FAILED",
    "Solo se pueden reintentar las tareas fallidas"
)

/** A month that does not exist, is after the current one or is outside the retention period. */
class InvalidSheetPeriodException(detail: String) :
    SheetsApiException(HttpStatus.BAD_REQUEST, "INVALID_PERIOD", "Mes no válido: $detail")

/** The spreadsheet a request writes to has not been configured yet (US2 scenario 7). */
class SheetsNotConfiguredException(role: SpreadsheetRole) : SheetsApiException(
    HttpStatus.CONFLICT,
    "SHEETS_NOT_CONFIGURED",
    "Primero hay que configurar la hoja de ${role.spanish} en la copia en Google Sheets"
)
