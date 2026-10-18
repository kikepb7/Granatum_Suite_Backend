package com.granatum.core

import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest
import com.google.api.services.sheets.v4.model.DeleteSheetRequest
import com.google.api.services.sheets.v4.model.Request
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.ServiceAccountCredentials
import com.granatum.core.domain.port.SheetCell
import com.granatum.core.infrastructure.google.GoogleSheetsClient
import com.granatum.core.infrastructure.google.SheetsGoogleSettings
import com.granatum.core.service.InvoiceSheetColumns
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real client against a real spreadsheet (research.md D-009). **Never in
 * CI nor in `./gradlew test`**: tagged `sheets-real`, it runs only through
 *
 *     GOOGLE_SHEETS_CREDENTIALS_FILE=/ruta/fuera/del/repo.json \
 *     SHEETS_TEST_SPREADSHEET_ID=<hoja de prueba de PRE> \
 *     ./gradlew :features:sheets:sheetsRealTest
 *
 * with PRE's service account and a test spreadsheet shared with it as Editor.
 * Without both variables every test is skipped. It writes only in a fresh tab
 * `test-<uuid>`, deleted at the end, so the spreadsheet is left as it was.
 */
@Tag("sheets-real")
class SheetsRealIT {

    private lateinit var client: GoogleSheetsClient
    private lateinit var api: Sheets
    private lateinit var spreadsheetId: String
    private val tab = "test-${UUID.randomUUID()}"

    @BeforeEach
    fun setUp() {
        val file = System.getenv("GOOGLE_SHEETS_CREDENTIALS_FILE").orEmpty()
        spreadsheetId = System.getenv("SHEETS_TEST_SPREADSHEET_ID").orEmpty()
        assumeTrue(file.isNotBlank() && spreadsheetId.isNotBlank(), "sin GOOGLE_SHEETS_CREDENTIALS_FILE o SHEETS_TEST_SPREADSHEET_ID")
        assumeTrue(Files.isReadable(Path.of(file)), "el fichero de credenciales no se puede leer")

        client = GoogleSheetsClient(SheetsGoogleSettings(credentialsFile = file))
        // A second handle, only to read back and to delete the test tab: the
        // port has no such operations, by design.
        val credentials = Files.newInputStream(Path.of(file)).use {
            ServiceAccountCredentials.fromStream(it).createScoped(listOf(SheetsScopes.SPREADSHEETS))
        }
        api = Sheets.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), HttpCredentialsAdapter(credentials))
            .setApplicationName("granatum-sheets-real-test")
            .build()
    }

    @AfterEach
    fun deleteTab() {
        if (!::api.isInitialized) return
        val sheet = api.spreadsheets().get(spreadsheetId).setFields("sheets.properties").execute()
            .sheets.orEmpty().firstOrNull { it.properties.title == tab } ?: return
        api.spreadsheets().batchUpdate(
            spreadsheetId,
            BatchUpdateSpreadsheetRequest().setRequests(
                listOf(Request().setDeleteSheet(DeleteSheetRequest().setSheetId(sheet.properties.sheetId)))
            )
        ).execute()
    }

    private fun values(): List<List<Any>> =
        api.spreadsheets().values().get(spreadsheetId, "'$tab'!A:Z").execute().getValues().orEmpty()

    @Test
    fun `the service account can edit the test spreadsheet`() {
        assertTrue(client.serviceAccountEmail().endsWith(".iam.gserviceaccount.com"))
        client.checkAccess(spreadsheetId)
    }

    @Test
    fun `a tab is rewritten whole, raw, and an invoice row is upserted and removed by id`() {
        client.rewriteTab(
            spreadsheetId, tab, listOf("Nombre", "Concepto"),
            listOf(listOf(SheetCell.text("Ana"), SheetCell.text("=1+1")))
        )
        client.rewriteTab(
            spreadsheetId, tab, listOf("Nombre", "Concepto"),
            listOf(listOf(SheetCell.text("Luis"), SheetCell.text("=2+2")))
        )
        assertEquals(listOf(listOf("Nombre", "Concepto"), listOf("Luis", "=2+2")), values(), "RAW: a formula stays text")

        val id = UUID.randomUUID()
        val cells = listOf(SheetCell.text("02/10/2026"), SheetCell.number(BigDecimal("176.00")))
        client.rewriteTab(spreadsheetId, tab, InvoiceSheetColumns.HEADER, emptyList(), hiddenIdColumn = true)
        client.upsertInvoiceRow(spreadsheetId, tab, InvoiceSheetColumns.HEADER, id, cells)
        client.upsertInvoiceRow(spreadsheetId, tab, InvoiceSheetColumns.HEADER, id, listOf(SheetCell.text("03/10/2026"), SheetCell.number(BigDecimal("10"))))
        val written = values()
        assertEquals(2, written.size, "header and one row: updated, not appended twice")
        assertEquals(id.toString(), written[1][0])
        assertEquals("03/10/2026", written[1][1])

        client.removeInvoiceRow(spreadsheetId, tab, id)
        assertEquals(1, values().size)
    }
}
