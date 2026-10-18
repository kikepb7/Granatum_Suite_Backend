package com.granatum.core

import com.granatum.core.domain.port.SheetsFailureReason
import com.granatum.core.domain.port.SheetsPermanentException
import com.granatum.core.domain.port.SheetsTransientException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** US4: configuring the two spreadsheets (contracts/README.md, research.md D-007). */
@SpringBootTest(classes = [SheetsTestApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WithFakeSheetsClient::class, SheetsPermissiveSecurityTestConfig::class)
class SheetsSettingsControllerIT : SheetsHttp() {

    @Autowired lateinit var client: FakeSheetsClient
    @Autowired lateinit var jdbc: JdbcTemplate

    private val shifts = "1AbCdEfGhIjKlMnOpQrStUvWxYz012345"
    private val invoices = "1XyZ_aBcDeFgHiJkLmNoPqRsTuV-98765"

    @BeforeEach
    fun clean() {
        jdbc.update("DELETE FROM sheet_settings")
        client.reset()
    }

    private fun put(shiftsInput: String, invoicesInput: String) = send(
        HttpMethod.PUT, "/api/sheets/settings",
        json.writeValueAsString(mapOf("shiftsSpreadsheet" to shiftsInput, "invoicesSpreadsheet" to invoicesInput))
    )

    private fun stored(): Int = jdbc.queryForObject("SELECT count(*) FROM sheet_settings", Int::class.java)!!

    @Test
    fun `without credentials the integration is reported unavailable`() {
        client.active = false
        val r = send(HttpMethod.GET, "/api/sheets/settings")

        assertEquals(200, r.status)
        assertFalse(r.body["available"].asBoolean())
        listOf("serviceAccountEmail", "shiftsSpreadsheetId", "shiftsSpreadsheetUrl", "invoicesSpreadsheetId",
            "invoicesSpreadsheetUrl", "configuredAt", "configuredBy").forEach {
            assertTrue(r.body[it] == null || r.body[it].isNull, "$it must be null")
        }
    }

    @Test
    fun `with credentials it shows the service account to share with, and no spreadsheets yet`() {
        val r = send(HttpMethod.GET, "/api/sheets/settings")

        assertEquals(200, r.status)
        assertTrue(r.body["available"].asBoolean())
        assertEquals(client.email, r.body["serviceAccountEmail"].asText())
        assertTrue(r.body["shiftsSpreadsheetId"].isNull)
        assertTrue(r.body["invoicesSpreadsheetId"].isNull)
    }

    @Test
    fun `a full link and a bare id are both accepted, checked and stored`() {
        val r = put("https://docs.google.com/spreadsheets/d/$shifts/edit#gid=0", invoices)

        assertEquals(200, r.status, r.raw)
        assertEquals(shifts, r.body["shiftsSpreadsheetId"].asText(), "id taken from the link")
        assertEquals("https://docs.google.com/spreadsheets/d/$shifts/edit", r.body["shiftsSpreadsheetUrl"].asText())
        assertEquals(invoices, r.body["invoicesSpreadsheetId"].asText())
        assertEquals("https://docs.google.com/spreadsheets/d/$invoices/edit", r.body["invoicesSpreadsheetUrl"].asText())
        assertEquals(admin.toString(), r.body["configuredBy"].asText())
        assertFalse(r.body["configuredAt"].isNull)

        assertEquals(listOf(shifts, invoices), client.calls.filter { it.operation == "checkAccess" }.map { it.spreadsheetId })
        assertTrue(client.calls.none { it.inTransaction }, "Google is checked outside any transaction")

        val again = send(HttpMethod.GET, "/api/sheets/settings")
        assertEquals(shifts, again.body["shiftsSpreadsheetId"].asText())
    }

    @Test
    fun `saving again replaces the spreadsheets`() {
        assertEquals(200, put(shifts, invoices).status)
        assertEquals(200, put(invoices, shifts).status)
        assertEquals(1, stored())
        assertEquals(invoices, send(HttpMethod.GET, "/api/sheets/settings").body["shiftsSpreadsheetId"].asText())
    }

    @Test
    fun `a spreadsheet the account cannot edit is refused with which one and why`() {
        for (reason in listOf(SheetsFailureReason.NOT_SHARED, SheetsFailureReason.READ_ONLY, SheetsFailureReason.NOT_FOUND)) {
            client.accessFailures.clear()
            client.accessFailures[invoices] = SheetsPermanentException(reason)

            val r = put(shifts, invoices)

            assertEquals(422, r.status, r.raw)
            assertEquals("SPREADSHEET_NOT_ACCESSIBLE", r.body["code"].asText())
            assertEquals("invoices", r.body["spreadsheet"].asText())
            assertEquals(reason.name, r.body["reason"].asText())
            assertTrue(r.body["message"].asText().contains(client.email), "says whom to share it with")
            assertTrue(r.body["message"].asText().contains("editora"))
        }
        client.accessFailures.clear()
        client.accessFailures[shifts] = SheetsPermanentException(SheetsFailureReason.NOT_SHARED)
        assertEquals("shifts", put(shifts, invoices).body["spreadsheet"].asText())
        assertEquals(0, stored())
    }

    @Test
    fun `the same spreadsheet for both is refused`() {
        val r = put("https://docs.google.com/spreadsheets/d/$shifts/edit", shifts)
        assertEquals(409, r.status)
        assertEquals("SAME_SPREADSHEET", r.body["code"].asText())
        assertEquals(0, stored())
    }

    @Test
    fun `Google not answering saves nothing`() {
        client.accessFailures[invoices] = SheetsTransientException("Google no ha podido atender la petición (HTTP 503)")
        val r = put(shifts, invoices)
        assertEquals(502, r.status)
        assertEquals("SHEETS_UNREACHABLE", r.body["code"].asText())
        assertEquals(0, stored())
    }

    @Test
    fun `text that is neither a link nor an id is a validation error`() {
        val cases = listOf(
            "hola" to invoices,
            shifts to "https://example.com/spreadsheets/x",
            "" to invoices,
            "a".repeat(501) to invoices
        )
        for ((s, i) in cases) {
            val r = put(s, i)
            assertEquals(400, r.status, "'${s.take(20)}' / '${i.take(20)}': ${r.raw}")
            assertEquals("VALIDATION", r.body["code"].asText())
        }
        assertEquals(0, stored())
    }

    @Test
    fun `without credentials saving is unavailable`() {
        client.active = false
        val r = put(shifts, invoices)
        assertEquals(503, r.status)
        assertEquals("SHEETS_UNAVAILABLE", r.body["code"].asText())
        assertEquals(0, stored())
    }
}
