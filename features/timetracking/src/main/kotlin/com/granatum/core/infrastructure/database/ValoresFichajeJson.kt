package com.granatum.core.infrastructure.database

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.granatum.core.domain.model.ValoresFichaje
import org.springframework.stereotype.Component

/**
 * Reads and writes the `valores_propuestos` / `valores_originales` JSONB
 * documents.
 *
 * ## Why its own mapper instead of the application's
 *
 * These documents are **persisted data, not a wire format**. Sharing the web
 * layer's `ObjectMapper` would mean that changing the API's Jackson
 * configuration later - a date format, a naming strategy, an inclusion rule -
 * silently changes how documents already in the database are interpreted. For
 * `valores_originales`, which is the legal evidence of what a shift said before
 * it was corrected, that is not an acceptable coupling.
 *
 * It also removes a dependency on autoconfiguration. In Boot 4 the
 * `ObjectMapper` bean comes from the separate `spring-boot-jackson` module,
 * which is on `app`'s classpath but not on this module's - so injecting one
 * worked in production and failed in this module's own tests. That is the third
 * time Boot 4's split autoconfiguration has caught this project out (after
 * Flyway and, nearly, scheduling), and configuring explicitly is how it stops
 * mattering.
 *
 * The settings are pinned deliberately:
 * - [JavaTimeModule] so `Instant` is read and written as ISO-8601 rather than a
 *   numeric timestamp.
 * - `WRITE_DATES_AS_TIMESTAMPS` disabled, for the same reason: a stored
 *   document should be legible to a human reading the table.
 * - `FAIL_ON_UNKNOWN_PROPERTIES` disabled, so a document written by an older
 *   version with an extra field can still be read. These rows outlive the code
 *   that wrote them by up to four years.
 */
@Component
class ValoresFichajeJson {

    private val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    fun escribir(valores: ValoresFichaje): String = mapper.writeValueAsString(valores)

    fun leer(json: String): ValoresFichaje = mapper.readValue(json, ValoresFichaje::class.java)
}
