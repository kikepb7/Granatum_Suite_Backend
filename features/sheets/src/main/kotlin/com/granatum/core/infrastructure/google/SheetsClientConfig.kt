package com.granatum.core.infrastructure.google

import com.granatum.core.domain.port.DisabledSheetsClient
import com.granatum.core.domain.port.SheetsClient
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files

/**
 * Chooses the client at startup (research.md D-001): the real one only with a
 * credentials file, the disabled one otherwise - and says so once.
 *
 * A path that is set but cannot be read stops the startup: someone meant to
 * turn the integration on, and silently running without it would only show up
 * as a queue that never empties. The message names the variable, never the
 * path or the content.
 */
@Configuration
class SheetsClientConfig {

    @Bean
    fun sheetsClient(settings: SheetsGoogleSettings): SheetsClient {
        if (!settings.active) {
            LoggerFactory.getLogger(javaClass).warn(
                "Copia en Google Sheets desactivada: sin GOOGLE_SHEETS_CREDENTIALS_FILE no se escribe en ninguna hoja"
            )
            return DisabledSheetsClient()
        }
        val path = settings.credentialsPath()
        check(Files.isRegularFile(path) && Files.isReadable(path)) {
            "El fichero de GOOGLE_SHEETS_CREDENTIALS_FILE no existe o no se puede leer"
        }
        return GoogleSheetsClient(settings)
    }
}
