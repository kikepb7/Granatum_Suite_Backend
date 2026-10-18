package com.granatum.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.granatum.core.domain.port.SheetCell
import com.granatum.core.domain.port.SheetsFailureReason
import com.granatum.core.domain.port.SheetsPermanentException
import com.granatum.core.domain.port.SheetsTransientException
import com.granatum.core.infrastructure.google.GoogleSheetsClient
import com.granatum.core.infrastructure.google.SheetsGoogleSettings
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The real client, against a local mock of Google's token endpoint and of the
 * Sheets API: no network, no real account (research.md D-009). It checks the
 * exact requests - `RAW`, ranges, `addSheet`, `deleteDimension` - and how each
 * HTTP answer is classified.
 *
 * The service-account file is generated here with an ephemeral RSA key, so the
 * real JWT signing and token exchange run end to end.
 */
class GoogleSheetsClientTest {

    @TempDir
    lateinit var dir: Path

    private lateinit var server: MockWebServer
    private lateinit var client: GoogleSheetsClient
    private val json = ObjectMapper()

    /** Every request to the Sheets API, in order (token requests apart). */
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val tokenRequests = CopyOnWriteArrayList<RecordedRequest>()

    /** The Sheets API's answers, in order. */
    private val script = ConcurrentLinkedQueue<MockResponse>()

    private val spreadsheet = "1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789"
    private val email = "granatum-test@proyecto-test.iam.gserviceaccount.com"

    @BeforeEach
    fun start() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path!!.startsWith("/oauth2/token")) {
                        tokenRequests += request
                        ok("""{"access_token":"ya29.token-de-prueba","expires_in":3600,"token_type":"Bearer"}""")
                    } else {
                        requests += request
                        script.poll() ?: MockResponse().setResponseCode(500).setBody("sin respuesta preparada")
                    }
            }
            start()
        }
        val credentials = dir.resolve("service-account.json")
        Files.writeString(credentials, serviceAccountJson())
        client = GoogleSheetsClient(
            SheetsGoogleSettings(
                credentialsFile = credentials.toString(),
                rootUrl = server.url("/").toString(),
                tokenUrl = server.url("/oauth2/token").toString(),
                connectTimeoutSeconds = 2,
                readTimeoutSeconds = 2
            )
        )
    }

    @AfterEach
    fun stop() = server.shutdown()

    // --- fixtures ------------------------------------------------------------

    /**
     * A service-account JSON as Google issues it, with a key made for this run.
     * Its `token_uri` points nowhere: the token must come from the configured
     * `token-url`.
     */
    private fun serviceAccountJson(): String {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keys.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"
        return json.writeValueAsString(
            mapOf(
                "type" to "service_account",
                "project_id" to "proyecto-test",
                "private_key_id" to "clave-efimera",
                "private_key" to pem,
                "client_email" to email,
                "client_id" to "1234567890",
                "token_uri" to "https://oauth2.invalid/token"
            )
        )
    }

    private fun ok(body: String = "{}") = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun error(status: Int, googleStatus: String) = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"error":{"code":$status,"message":"mensaje de Google","status":"$googleStatus"}}""")

    private fun tabsResponse(vararg tabs: Pair<Int, String>) = ok(
        """{"sheets":[${tabs.joinToString(",") { (id, title) -> """{"properties":{"sheetId":$id,"title":"$title"}}""" }}]}"""
    )

    private fun idColumn(vararg ids: String) = ok(
        """{"range":"'2026-10'!A1:A${ids.size}","majorDimension":"ROWS","values":[${ids.joinToString(",") { "[\"$it\"]" }}]}"""
    )

    private fun RecordedRequest.decodedPath(): String =
        URLDecoder.decode(requestUrl!!.encodedPath.replace("+", "%2B"), Charsets.UTF_8)

    /** The JSON body; the Google client gzips it. */
    private fun RecordedRequest.body(): JsonNode {
        val bytes = body.clone().readByteArray()
        val raw = if (getHeader("Content-Encoding") == "gzip") GZIPInputStream(bytes.inputStream()).readBytes() else bytes
        return json.readTree(raw)
    }

    private fun RecordedRequest.describe() = "$method ${decodedPath()}"

    private val base = "/v4/spreadsheets/$spreadsheet"

    // --- token ---------------------------------------------------------------

    @Test
    fun `the token is obtained from the service account with the spreadsheets scope only`() {
        script += tabsResponse(0 to "Hoja 1", 1 to "2026-10")
        script += ok()
        script += ok()
        client.rewriteTab(spreadsheet, "2026-10", listOf("Nombre"), emptyList())

        val token = tokenRequests.single()
        val form = token.body.readUtf8().split("&").associate {
            val (k, v) = it.split("=", limit = 2)
            k to URLDecoder.decode(v, Charsets.UTF_8)
        }
        assertEquals("urn:ietf:params:oauth:grant-type:jwt-bearer", form["grant_type"])
        val claims = json.readTree(Base64.getUrlDecoder().decode(form["assertion"]!!.split(".")[1]))
        assertEquals("https://www.googleapis.com/auth/spreadsheets", claims["scope"].asText())
        assertEquals(email, claims["iss"].asText())

        requests.forEach {
            assertEquals("Bearer ya29.token-de-prueba", it.getHeader("Authorization"))
        }
        assertEquals(email, client.serviceAccountEmail(), "read from the credentials, never from the code")
    }

    // --- rewriteTab ----------------------------------------------------------

    @Test
    fun `rewriting a missing tab adds it, clears it and writes everything with RAW`() {
        script += tabsResponse(0 to "Hoja 1")
        script += ok() // addSheet
        script += ok() // clear
        script += ok() // update

        client.rewriteTab(
            spreadsheet, "2026-10",
            listOf("Nombre", "Importe"),
            listOf(
                listOf(SheetCell.text("=HYPERLINK(\"x\")"), SheetCell.number(BigDecimal("176.00"))),
                listOf(SheetCell.text("Ana"), SheetCell.number(BigDecimal("0.50")))
            )
        )

        assertEquals(
            listOf(
                "GET $base",
                "POST $base:batchUpdate",
                "POST $base/values/'2026-10'!A:Z:clear",
                "PUT $base/values/'2026-10'!A1"
            ),
            requests.map { it.describe() }
        )
        assertEquals("sheets.properties", requests[0].requestUrl!!.queryParameter("fields"))

        val add = requests[1].body()["requests"]
        assertEquals(1, add.size(), "only the tab: shifts have no hidden id column")
        assertEquals("2026-10", add[0]["addSheet"]["properties"]["title"].asText())

        val update = requests[3]
        assertEquals("RAW", update.requestUrl!!.queryParameter("valueInputOption"), "no text is read as a formula")
        val values = update.body()["values"]
        assertEquals(3, values.size())
        assertEquals("Nombre", values[0][0].asText())
        assertTrue(values[1][0].isTextual)
        assertEquals("=HYPERLINK(\"x\")", values[1][0].asText())
        assertTrue(values[1][1].isNumber, "amounts go as numbers the sheet can sum")
        assertEquals(BigDecimal("176.00"), values[1][1].decimalValue().setScale(2))
    }

    @Test
    fun `rewriting an existing tab does not add it again`() {
        script += tabsResponse(0 to "Hoja 1", 55 to "2026-10")
        script += ok()
        script += ok()

        client.rewriteTab(spreadsheet, "2026-10", listOf("Nombre"), emptyList())

        assertEquals(
            listOf("GET $base", "POST $base/values/'2026-10'!A:Z:clear", "PUT $base/values/'2026-10'!A1"),
            requests.map { it.describe() }
        )
    }

    @Test
    fun `a receipts tab is created with column A hidden and protected`() {
        script += tabsResponse(0 to "Hoja 1")
        script += ok()
        script += ok()
        script += ok()

        client.rewriteTab(spreadsheet, "2026-10", listOf("Id", "Fecha"), emptyList(), hiddenIdColumn = true)

        val batch = requests[1].body()["requests"]
        val sheetId = batch[0]["addSheet"]["properties"]["sheetId"].asInt()
        val hide = batch[1]["updateDimensionProperties"]
        assertEquals(sheetId, hide["range"]["sheetId"].asInt())
        assertEquals("COLUMNS", hide["range"]["dimension"].asText())
        assertEquals(0, hide["range"]["startIndex"]?.asInt() ?: 0)
        assertEquals(1, hide["range"]["endIndex"].asInt())
        assertTrue(hide["properties"]["hiddenByUser"].asBoolean())
        val protect = batch[2]["addProtectedRange"]["protectedRange"]
        assertEquals(sheetId, protect["range"]["sheetId"].asInt())
        assertEquals(1, protect["range"]["endColumnIndex"].asInt())
        assertTrue(protect["warningOnly"].asBoolean(), "a warning, so a person can still delete rows")
    }

    // --- upsertInvoiceRow ----------------------------------------------------

    @Test
    fun `an invoice already in the tab is updated in its own row`() {
        val invoice = UUID.randomUUID()
        script += tabsResponse(7 to "2026-10")
        script += idColumn("Id", UUID.randomUUID().toString(), invoice.toString())
        script += ok()

        client.upsertInvoiceRow(spreadsheet, "2026-10", listOf("Id", "Total"), invoice, listOf(SheetCell.number(BigDecimal("10.00"))))

        assertEquals(
            listOf("GET $base", "GET $base/values/'2026-10'!A:A", "PUT $base/values/'2026-10'!A3"),
            requests.map { it.describe() }
        )
        val update = requests[2]
        assertEquals("RAW", update.requestUrl!!.queryParameter("valueInputOption"))
        val row = update.body()["values"][0]
        assertEquals(invoice.toString(), row[0].asText(), "the id goes in column A")
        assertTrue(row[1].isNumber)
    }

    @Test
    fun `an invoice not in the tab is appended`() {
        val invoice = UUID.randomUUID()
        script += tabsResponse(7 to "2026-10")
        script += idColumn("Id", UUID.randomUUID().toString())
        script += ok()

        client.upsertInvoiceRow(spreadsheet, "2026-10", listOf("Id", "Total"), invoice, listOf(SheetCell.text("x")))

        assertEquals("POST $base/values/'2026-10'!A1:append", requests[2].describe())
        assertEquals("RAW", requests[2].requestUrl!!.queryParameter("valueInputOption"))
        assertEquals("INSERT_ROWS", requests[2].requestUrl!!.queryParameter("insertDataOption"))
        assertEquals(invoice.toString(), requests[2].body()["values"][0][0].asText())
    }

    @Test
    fun `an invoice for a month without tab creates it with the header`() {
        val invoice = UUID.randomUUID()
        script += tabsResponse(0 to "Hoja 1")
        script += ok()
        script += ok()

        client.upsertInvoiceRow(spreadsheet, "2026-11", listOf("Id", "Total"), invoice, listOf(SheetCell.text("x")))

        assertEquals(
            listOf("GET $base", "POST $base:batchUpdate", "PUT $base/values/'2026-11'!A1"),
            requests.map { it.describe() }
        )
        assertEquals(3, requests[1].body()["requests"].size(), "tab, hidden column, protection")
        val values = requests[2].body()["values"]
        assertEquals("Id", values[0][0].asText())
        assertEquals(invoice.toString(), values[1][0].asText())
    }

    // --- removeInvoiceRow ----------------------------------------------------

    @Test
    fun `removing an invoice deletes its row in the right sheet`() {
        val invoice = UUID.randomUUID()
        script += tabsResponse(0 to "Hoja 1", 777 to "2026-10")
        script += idColumn("Id", UUID.randomUUID().toString(), invoice.toString())
        script += ok()

        client.removeInvoiceRow(spreadsheet, "2026-10", invoice)

        assertEquals("POST $base:batchUpdate", requests[2].describe())
        val range = requests[2].body()["requests"][0]["deleteDimension"]["range"]
        assertEquals(777, range["sheetId"].asInt())
        assertEquals("ROWS", range["dimension"].asText())
        assertEquals(2, range["startIndex"].asInt())
        assertEquals(3, range["endIndex"].asInt())
    }

    @Test
    fun `removing an invoice that is not there changes nothing`() {
        script += tabsResponse(777 to "2026-10")
        script += idColumn("Id")
        client.removeInvoiceRow(spreadsheet, "2026-10", UUID.randomUUID())
        assertEquals(2, requests.size)

        requests.clear()
        script += tabsResponse(0 to "Hoja 1")
        client.removeInvoiceRow(spreadsheet, "2026-10", UUID.randomUUID())
        assertEquals(1, requests.size, "no tab, nothing to read")
    }

    // --- errors --------------------------------------------------------------

    @Test
    fun `403 is not shared and 404 is not found, both permanent`() {
        script += error(403, "PERMISSION_DENIED")
        assertEquals(
            SheetsFailureReason.NOT_SHARED,
            assertFailsWith<SheetsPermanentException> { client.rewriteTab(spreadsheet, "2026-10", listOf("x"), emptyList()) }.reason
        )

        script += error(404, "NOT_FOUND")
        assertEquals(
            SheetsFailureReason.NOT_FOUND,
            assertFailsWith<SheetsPermanentException> { client.rewriteTab(spreadsheet, "2026-10", listOf("x"), emptyList()) }.reason
        )

        script += error(400, "INVALID_ARGUMENT")
        assertEquals(
            SheetsFailureReason.BAD_REQUEST,
            assertFailsWith<SheetsPermanentException> { client.rewriteTab(spreadsheet, "2026-10", listOf("x"), emptyList()) }.reason
        )
    }

    @Test
    fun `429 and 503 are transient, and no message quotes Google's text`() {
        script += error(429, "RESOURCE_EXHAUSTED")
        val quota = assertFailsWith<SheetsTransientException> { client.rewriteTab(spreadsheet, "2026-10", listOf("x"), emptyList()) }
        assertFalse(quota.message!!.contains("mensaje de Google"))

        script += error(503, "UNAVAILABLE")
        assertFailsWith<SheetsTransientException> { client.rewriteTab(spreadsheet, "2026-10", listOf("x"), emptyList()) }
    }

    @Test
    fun `a server that does not answer in time is transient`() {
        script += ok().setHeadersDelay(5, java.util.concurrent.TimeUnit.SECONDS)
        assertFailsWith<SheetsTransientException> { client.rewriteTab(spreadsheet, "2026-10", listOf("x"), emptyList()) }
    }

    // --- checkAccess ---------------------------------------------------------

    @Test
    fun `access is checked by rewriting the same title`() {
        script += ok("""{"spreadsheetId":"$spreadsheet","properties":{"title":"Fichajes Granatum"}}""")
        script += ok()

        client.checkAccess(spreadsheet)

        assertEquals(listOf("GET $base", "POST $base:batchUpdate"), requests.map { it.describe() })
        assertEquals("spreadsheetId,properties.title", requests[0].requestUrl!!.queryParameter("fields"))
        val update = requests[1].body()["requests"][0]["updateSpreadsheetProperties"]
        assertEquals("Fichajes Granatum", update["properties"]["title"].asText(), "the same title: nothing changes")
        assertEquals("title", update["fields"].asText())
    }

    @Test
    fun `a spreadsheet shared only as reader is read-only, and an unshared one is not shared`() {
        script += ok("""{"spreadsheetId":"$spreadsheet","properties":{"title":"Fichajes"}}""")
        script += error(403, "PERMISSION_DENIED")
        assertEquals(SheetsFailureReason.READ_ONLY, assertFailsWith<SheetsPermanentException> { client.checkAccess(spreadsheet) }.reason)

        script += error(403, "PERMISSION_DENIED")
        assertEquals(SheetsFailureReason.NOT_SHARED, assertFailsWith<SheetsPermanentException> { client.checkAccess(spreadsheet) }.reason)

        script += error(404, "NOT_FOUND")
        assertEquals(SheetsFailureReason.NOT_FOUND, assertFailsWith<SheetsPermanentException> { client.checkAccess(spreadsheet) }.reason)
    }

    @Test
    fun `the client says what it is without the credentials`() {
        assertEquals("GoogleSheetsClient", client.toString())
        val settings = SheetsGoogleSettings(credentialsFile = dir.resolve("service-account.json").toString())
        assertFalse(settings.toString().contains(dir.toString()), "the path is a secret's location")
    }
}
