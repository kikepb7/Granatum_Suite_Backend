package com.granatum.core.infrastructure.storage

import com.granatum.core.domain.port.MaterialImageStorage
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Chooses the image storage at startup: Supabase only with a URL and a key,
 * none otherwise. Without them the application still starts (research.md
 * D-010), and says so once.
 */
@Configuration
class MaterialImageStorageConfig {

    @Bean
    fun materialImageStorage(properties: MaterialImageStorageProperties): MaterialImageStorage =
        if (properties.enabled) {
            SupabaseMaterialImageStorage(properties)
        } else {
            LoggerFactory.getLogger(javaClass).warn(
                "Inventario sin almacenamiento de imagenes: sin SUPABASE_URL y SUPABASE_SERVICE_ROLE_KEY no se pueden subir imagenes"
            )
            DisabledMaterialImageStorage()
        }
}
