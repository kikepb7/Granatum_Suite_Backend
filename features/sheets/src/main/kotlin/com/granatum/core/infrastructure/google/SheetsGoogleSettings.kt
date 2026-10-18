package com.granatum.core.infrastructure.google

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Where the Google settings come from - and the **only** place the
 * credentials come from (research.md D-001).
 *
 * `sheets.google.credentials-file` is mapped from GOOGLE_SHEETS_CREDENTIALS_FILE
 * with an empty default: the path to a service-account JSON kept outside the
 * repository. Empty means the integration is off. The Google libraries are
 * never left to find credentials by themselves (`getApplicationDefault()`,
 * GOOGLE_APPLICATION_CREDENTIALS, gcloud, metadata server), so an environment
 * or a test "without credentials" cannot authenticate with whatever the
 * machine happens to have.
 *
 * The path is a secret's location: [toString] shows neither it nor the content.
 */
@Component
class SheetsGoogleSettings(
    @param:Value("\${sheets.google.credentials-file:}") private val credentialsFile: String,
    /** Empty = Google's. Set only by GoogleSheetsClientTest, to its local mock server. */
    @param:Value("\${sheets.google.root-url:}") val rootUrl: String = "",
    /** Empty = the `token_uri` of the credentials. Set only by GoogleSheetsClientTest. */
    @param:Value("\${sheets.google.token-url:}") val tokenUrl: String = "",
    @param:Value("\${sheets.google.connect-timeout-seconds:10}") val connectTimeoutSeconds: Int = 10,
    @param:Value("\${sheets.google.read-timeout-seconds:30}") val readTimeoutSeconds: Int = 30
) {
    val active: Boolean get() = credentialsFile.isNotBlank()

    /** The file, for the one place that reads it. Never logged. */
    fun credentialsPath(): Path {
        check(active) { "sin credenciales de Google Sheets" }
        return Path.of(credentialsFile.trim())
    }

    override fun toString(): String = "SheetsGoogleSettings(active=$active)"
}
