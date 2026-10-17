package com.granatum.core.infrastructure.storage

import com.granatum.core.domain.exception.AlmacenImagenesNoDisponibleException
import com.granatum.core.domain.port.AlmacenImagenes
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.net.http.HttpClient
import java.time.Duration

/**
 * Supabase Storage through its REST API (research.md D-001): there is no
 * official Java SDK, and the product needs three calls.
 *
 * The JDK's HTTP client and not `HttpURLConnection`: deleting objects is a
 * `DELETE` with a JSON body, which the JDK client sends as-is.
 */
class AlmacenImagenesSupabase(private val config: ConfiguracionAlmacenImagenes) : AlmacenImagenes {

    private val base = config.supabaseUrl.trimEnd('/') + "/storage/v1"

    private val cliente: RestClient = RestClient.builder()
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(config.timeoutSegundos)).build()
            ).apply { setReadTimeout(Duration.ofSeconds(config.timeoutSegundos)) }
        )
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${config.clave()}")
        .defaultHeader("apikey", config.clave())
        .build()

    override val disponible: Boolean = true

    override fun guardar(ruta: String, contenido: ByteArray, mediaType: String) {
        llamar {
            cliente.post()
                .uri("$base/object/${config.bucket}/${segura(ruta)}")
                .contentType(MediaType.parseMediaType(mediaType))
                // Every upload has a new path (D-009): an existing object here
                // would be a bug, and failing beats silently replacing it.
                .header("x-upsert", "false")
                .body(contenido)
                .retrieve()
                .toBodilessEntity()
        }
    }

    override fun borrar(rutas: Collection<String>) {
        if (rutas.isEmpty()) return
        llamar {
            cliente.method(HttpMethod.DELETE)
                .uri("$base/object/${config.bucket}")
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("prefixes" to rutas.map(::segura)))
                .retrieve()
                .toBodilessEntity()
        }
    }

    override fun firmar(rutas: Collection<String>): Map<String, String> {
        if (rutas.isEmpty()) return emptyMap()
        val firmadas = llamar {
            cliente.post()
                .uri("$base/object/sign/${config.bucket}")
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("expiresIn" to config.urlSegundos, "paths" to rutas.map(::segura)))
                .retrieve()
                .body(object : ParameterizedTypeReference<List<Map<String, Any?>>>() {})
        }.orEmpty()
        // signedURL comes relative to /storage/v1; a path Storage could not
        // sign carries an error and no URL, and is left out.
        return firmadas.mapNotNull { f ->
            val ruta = f["path"] as? String
            val url = f["signedURL"] as? String
            if (ruta == null || url == null) null else ruta to base + url
        }.toMap()
    }

    /**
     * Paths are built by the service from UUIDs and an extension (D-009), so
     * this never fails in practice; it is what makes concatenating them into
     * the URL safe if that ever changes.
     */
    private fun segura(ruta: String): String {
        require(RUTA.matches(ruta)) { "ruta de imagen no valida" }
        return ruta
    }

    private fun <T> llamar(llamada: () -> T): T =
        try {
            llamada()
        } catch (e: RestClientException) {
            throw AlmacenImagenesNoDisponibleException(e)
        }

    private companion object {
        val RUTA = Regex("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*\\.[a-z]+")
    }
}
