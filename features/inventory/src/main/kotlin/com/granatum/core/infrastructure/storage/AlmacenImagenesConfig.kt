package com.granatum.core.infrastructure.storage

import com.granatum.core.domain.port.AlmacenImagenes
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Chooses the image storage at startup: Supabase only with a URL and a key,
 * none otherwise. Without them the application still starts (research.md
 * D-010), and says so once.
 */
@Configuration
class AlmacenImagenesConfig {

    @Bean
    fun almacenImagenes(config: ConfiguracionAlmacenImagenes): AlmacenImagenes =
        if (config.activo) {
            AlmacenImagenesSupabase(config)
        } else {
            LoggerFactory.getLogger(javaClass).warn(
                "Inventario sin almacenamiento de imagenes: sin SUPABASE_URL y SUPABASE_SERVICE_ROLE_KEY no se pueden subir imagenes"
            )
            AlmacenImagenesDeshabilitado()
        }
}
