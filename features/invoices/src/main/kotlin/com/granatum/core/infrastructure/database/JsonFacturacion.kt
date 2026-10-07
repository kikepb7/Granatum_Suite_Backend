package com.granatum.core.infrastructure.database

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.stereotype.Component

/**
 * Writes the JSONB documents of invoicing (proposals, previous values, close
 * snapshots). Its own mapper, as timetracking does for its stored documents:
 * these are persisted data, and changing the API's Jackson settings must not
 * change how they are written.
 */
@Component
class JsonFacturacion {
    val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    fun escribir(valor: Any): String = mapper.writeValueAsString(valor)
}
