package com.granatum.core.api.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.convert.DurationStyle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * The per-origin quotas (feature 006, research.md D-003), read from
 * `seguridad.limites.*`.
 *
 * Each quota is one value, `N/period` - `10/1m`, `5/1h` - so a deployment
 * changes it with a single variable. `off` disables that quota.
 */
@Configuration
class ConfiguracionLimites {

    @Bean
    fun limitadorPorOrigen(
        @Value("\${seguridad.limites.login}") login: String,
        @Value("\${seguridad.limites.sesion}") sesion: String,
        @Value("\${seguridad.limites.registro}") registro: String,
        @Value("\${seguridad.limites.general}") general: String,
        @Value("\${seguridad.limites.max-direcciones}") maxDirecciones: Int
    ): LimitadorPorOrigen = LimitadorPorOrigen(
        cupos = buildMap {
            leer("seguridad.limites.login", login)?.let { put(Cupo.LOGIN, it) }
            leer("seguridad.limites.sesion", sesion)?.let { put(Cupo.SESION, it) }
            leer("seguridad.limites.registro", registro)?.let { put(Cupo.REGISTRO, it) }
            leer("seguridad.limites.general", general)?.let { put(Cupo.GENERAL, it) }
        },
        maxDirecciones = maxDirecciones
    )

    companion object {
        /**
         * Parses `N/period`. A malformed value stops the application from
         * starting, naming the property: a typo must not silently mean "no
         * limit" on the sign-in route.
         */
        fun leer(propiedad: String, valor: String): Pair<Int, Duration>? {
            if (valor.trim().equals("off", ignoreCase = true)) return null
            val partes = valor.split("/")
            val capacidad = partes.getOrNull(0)?.trim()?.toIntOrNull()
            val periodo = partes.getOrNull(1)?.trim()?.let { runCatching { DurationStyle.SIMPLE.parse(it) }.getOrNull() }
            check(partes.size == 2 && capacidad != null && capacidad > 0 && periodo != null && !periodo.isNegative && !periodo.isZero) {
                "$propiedad debe tener la forma N/periodo (por ejemplo 10/1m o 5/1h) u 'off'"
            }
            return capacidad to periodo
        }
    }
}
