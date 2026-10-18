package com.granatum.core.infrastructure.google

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.HttpResponseException
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.AddProtectedRangeRequest
import com.google.api.services.sheets.v4.model.AddSheetRequest
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest
import com.google.api.services.sheets.v4.model.ClearValuesRequest
import com.google.api.services.sheets.v4.model.DeleteDimensionRequest
import com.google.api.services.sheets.v4.model.DimensionProperties
import com.google.api.services.sheets.v4.model.DimensionRange
import com.google.api.services.sheets.v4.model.GridRange
import com.google.api.services.sheets.v4.model.ProtectedRange
import com.google.api.services.sheets.v4.model.Request
import com.google.api.services.sheets.v4.model.SheetProperties
import com.google.api.services.sheets.v4.model.SpreadsheetProperties
import com.google.api.services.sheets.v4.model.UpdateDimensionPropertiesRequest
import com.google.api.services.sheets.v4.model.UpdateSpreadsheetPropertiesRequest
import com.google.api.services.sheets.v4.model.ValueRange
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.ServiceAccountCredentials
import com.granatum.core.domain.port.SheetCell
import com.granatum.core.domain.port.SheetsClient
import com.granatum.core.domain.port.SheetsFailureReason
import com.granatum.core.domain.port.SheetsPermanentException
import com.granatum.core.domain.port.SheetsTransientException
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.util.UUID
import kotlin.random.Random

/**
 * The real client, with the official Google library (research.md D-001, D-004,
 * D-007).
 *
 * Credentials come **only** from the service-account file of
 * [SheetsGoogleSettings], scoped to `spreadsheets` - never from
 * `getApplicationDefault()`. Everything is written with `RAW`.
 *
 * Errors: 400 → `BAD_REQUEST`, 403 → `NOT_SHARED` (`API_DISABLED` when the
 * Sheets API is off in the project, `READ_ONLY` when the
 * read worked and the write did not), 404 → `NOT_FOUND`, all permanent; 429,
 * 408, 5xx, a 403 for quota, network, timeouts and token failures are
 * transient. No message carries Google's own text, the credentials or the
 * sheet's content.
 */
class GoogleSheetsClient(settings: SheetsGoogleSettings) : SheetsClient {

    companion object {
        const val APPLICATION_NAME = "Granatum Suite"
        private const val RAW = "RAW"
        private val QUOTA_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded", "quotaExceeded")
    }

    private val credentials: ServiceAccountCredentials = loadCredentials(settings)

    private val sheets: Sheets = run {
        val adapter = HttpCredentialsAdapter(credentials)
        val initializer = HttpRequestInitializer { request ->
            adapter.initialize(request)
            request.connectTimeout = settings.connectTimeoutSeconds * 1000
            request.readTimeout = settings.readTimeoutSeconds * 1000
        }
        Sheets.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), initializer)
            .setApplicationName(APPLICATION_NAME)
            .apply { if (settings.rootUrl.isNotBlank()) setRootUrl(settings.rootUrl.trim().let { if (it.endsWith("/")) it else "$it/" }) }
            .build()
    }

    override val active: Boolean = true

    override fun serviceAccountEmail(): String = credentials.clientEmail

    override fun checkAccess(spreadsheetId: String) {
        val title = call(SheetsFailureReason.NOT_SHARED) {
            sheets.spreadsheets().get(spreadsheetId).setFields("spreadsheetId,properties.title").execute()
        }.properties?.title.orEmpty()
        // Rewriting the same title proves edit permission without changing anything.
        call(SheetsFailureReason.READ_ONLY) {
            sheets.spreadsheets().batchUpdate(
                spreadsheetId,
                BatchUpdateSpreadsheetRequest().setRequests(
                    listOf(
                        Request().setUpdateSpreadsheetProperties(
                            UpdateSpreadsheetPropertiesRequest()
                                .setProperties(SpreadsheetProperties().setTitle(title))
                                .setFields("title")
                        )
                    )
                )
            ).execute()
        }
    }

    override fun rewriteTab(
        spreadsheetId: String,
        tab: String,
        header: List<String>,
        rows: List<List<SheetCell>>,
        hiddenIdColumn: Boolean
    ) {
        ensureTab(spreadsheetId, tab, hiddenIdColumn)
        call {
            sheets.spreadsheets().values().clear(spreadsheetId, "${quote(tab)}!A:Z", ClearValuesRequest()).execute()
        }
        write(spreadsheetId, "${quote(tab)}!A1", listOf(header) + rows.map { cells(it) })
    }

    override fun upsertInvoiceRow(spreadsheetId: String, tab: String, header: List<String>, invoiceId: UUID, row: List<SheetCell>) {
        val full = cells(listOf(SheetCell.text(invoiceId.toString())) + row)
        val tabInfo = ensureTab(spreadsheetId, tab, hiddenIdColumn = true)
        val ids = if (tabInfo.created) emptyList() else idColumn(spreadsheetId, tab)
        when {
            ids.isEmpty() -> write(spreadsheetId, "${quote(tab)}!A1", listOf(header, full))
            ids.contains(invoiceId.toString()) ->
                write(spreadsheetId, "${quote(tab)}!A${ids.indexOf(invoiceId.toString()) + 1}", listOf(full))
            else -> call {
                sheets.spreadsheets().values()
                    .append(spreadsheetId, "${quote(tab)}!A1", ValueRange().setValues(listOf(full)))
                    .setValueInputOption(RAW)
                    .setInsertDataOption("INSERT_ROWS")
                    .execute()
            }
        }
    }

    override fun removeInvoiceRow(spreadsheetId: String, tab: String, invoiceId: UUID) {
        val sheet = tabs(spreadsheetId).firstOrNull { it.title == tab } ?: return
        val index = idColumn(spreadsheetId, tab).indexOf(invoiceId.toString())
        if (index < 0) return
        call {
            sheets.spreadsheets().batchUpdate(
                spreadsheetId,
                BatchUpdateSpreadsheetRequest().setRequests(
                    listOf(
                        Request().setDeleteDimension(
                            DeleteDimensionRequest().setRange(
                                DimensionRange().setSheetId(sheet.sheetId).setDimension("ROWS")
                                    .setStartIndex(index).setEndIndex(index + 1)
                            )
                        )
                    )
                )
            ).execute()
        }
    }

    // --- helpers -------------------------------------------------------------

    private data class TabInfo(val sheetId: Int, val created: Boolean)

    private fun tabs(spreadsheetId: String): List<SheetProperties> =
        call { sheets.spreadsheets().get(spreadsheetId).setFields("sheets.properties").execute() }
            .sheets.orEmpty().mapNotNull { it.properties }

    /**
     * The tab's `sheetId`, adding the tab if missing - in one `batchUpdate`
     * with, for receipts, column A hidden and protected (warning only, so a
     * person can still delete rows).
     */
    private fun ensureTab(spreadsheetId: String, tab: String, hiddenIdColumn: Boolean): TabInfo {
        val existing = tabs(spreadsheetId)
        existing.firstOrNull { it.title == tab }?.let { return TabInfo(it.sheetId, false) }

        val taken = existing.map { it.sheetId }.toSet()
        val sheetId = generateSequence { Random.nextInt(1, Int.MAX_VALUE) }.first { it !in taken }
        val requests = mutableListOf(
            Request().setAddSheet(AddSheetRequest().setProperties(SheetProperties().setSheetId(sheetId).setTitle(tab)))
        )
        if (hiddenIdColumn) {
            requests += Request().setUpdateDimensionProperties(
                UpdateDimensionPropertiesRequest()
                    .setRange(DimensionRange().setSheetId(sheetId).setDimension("COLUMNS").setStartIndex(0).setEndIndex(1))
                    .setProperties(DimensionProperties().setHiddenByUser(true))
                    .setFields("hiddenByUser")
            )
            requests += Request().setAddProtectedRange(
                AddProtectedRangeRequest().setProtectedRange(
                    ProtectedRange()
                        .setRange(GridRange().setSheetId(sheetId).setStartColumnIndex(0).setEndColumnIndex(1))
                        .setDescription("Identificador de la factura en Granatum: no editar")
                        .setWarningOnly(true)
                )
            )
        }
        try {
            call {
                sheets.spreadsheets().batchUpdate(spreadsheetId, BatchUpdateSpreadsheetRequest().setRequests(requests)).execute()
            }
        } catch (e: SheetsPermanentException) {
            // Another worker may have added it a moment ago.
            if (e.reason != SheetsFailureReason.BAD_REQUEST) throw e
            val now = tabs(spreadsheetId).firstOrNull { it.title == tab } ?: throw e
            return TabInfo(now.sheetId, false)
        }
        return TabInfo(sheetId, true)
    }

    private fun idColumn(spreadsheetId: String, tab: String): List<String> =
        call { sheets.spreadsheets().values().get(spreadsheetId, "${quote(tab)}!A:A").execute() }
            .getValues().orEmpty().map { it.firstOrNull()?.toString().orEmpty() }

    private fun write(spreadsheetId: String, range: String, values: List<List<Any>>) {
        call {
            sheets.spreadsheets().values()
                .update(spreadsheetId, range, ValueRange().setValues(values))
                .setValueInputOption(RAW)
                .execute()
        }
    }

    private fun cells(row: List<SheetCell>): List<Any> = row.map {
        when (it) {
            is SheetCell.Text -> it.value
            is SheetCell.Number -> it.value
        }
    }

    /** A1 notation: the tab name between single quotes, quotes doubled. */
    private fun quote(tab: String) = "'" + tab.replace("'", "''") + "'"

    private fun <T> call(onForbidden: SheetsFailureReason = SheetsFailureReason.NOT_SHARED, block: () -> T): T =
        try {
            block()
        } catch (e: GoogleJsonResponseException) {
            val quota = e.details?.errors.orEmpty().any { it.reason in QUOTA_REASONS } ||
                e.details?.get("status") == "RESOURCE_EXHAUSTED"
            // A 403 also arrives when the Sheets API is not enabled in the
            // service account's project: that is not a sharing problem.
            val apiDisabled = e.content.orEmpty().let { "SERVICE_DISABLED" in it || "accessNotConfigured" in it }
            throw if (e.statusCode == 403 && apiDisabled) SheetsPermanentException(SheetsFailureReason.API_DISABLED)
            else classify(e.statusCode, onForbidden, quota)
        } catch (e: HttpResponseException) {
            throw classify(e.statusCode, onForbidden, quota = false)
        } catch (e: IOException) {
            // Network, timeout, or the token could not be obtained.
            throw SheetsTransientException("No se ha podido completar la petición a Google (red, tiempo agotado o credenciales)")
        }

    private fun classify(status: Int, onForbidden: SheetsFailureReason, quota: Boolean): RuntimeException = when {
        status == 429 || (status == 403 && quota) ->
            SheetsTransientException("Google ha limitado temporalmente las peticiones (cuota)")
        status == 403 -> SheetsPermanentException(onForbidden)
        status == 404 -> SheetsPermanentException(SheetsFailureReason.NOT_FOUND)
        status == 408 || status == 401 || status >= 500 ->
            SheetsTransientException("Google no ha podido atender la petición (HTTP $status)")
        else -> SheetsPermanentException(SheetsFailureReason.BAD_REQUEST)
    }

    override fun toString(): String = "GoogleSheetsClient"

    private fun loadCredentials(settings: SheetsGoogleSettings): ServiceAccountCredentials {
        val scoped = try {
            Files.newInputStream(settings.credentialsPath()).use { stream ->
                ServiceAccountCredentials.fromStream(stream).createScoped(SheetsScopes.SPREADSHEETS)
            }
        } catch (e: Exception) {
            // Not chained: a parser's message can quote the file.
            throw IllegalStateException(
                "El fichero de GOOGLE_SHEETS_CREDENTIALS_FILE no es un JSON de cuenta de servicio de Google válido"
            )
        } as ServiceAccountCredentials
        return if (settings.tokenUrl.isBlank()) scoped
        else scoped.toBuilder().setTokenServerUri(URI(settings.tokenUrl.trim())).build()
    }
}
