package com.granatum.core.infrastructure.claude

import com.granatum.core.domain.port.ReconocedorFacturas
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Chooses the recogniser at startup: the real one only with a key, manual mode
 * otherwise. Without a key the application still starts (research.md D-006),
 * and says so once.
 */
@Configuration
class ReconocedorConfig {

    @Bean
    fun reconocedorFacturas(config: ConfiguracionClaude): ReconocedorFacturas =
        if (config.activo) {
            ReconocedorClaude(config)
        } else {
            LoggerFactory.getLogger(javaClass).warn(
                "Facturacion en modo manual: sin ANTHROPIC_API_KEY no se reconocen facturas automaticamente"
            )
            ReconocedorDeshabilitado()
        }
}
