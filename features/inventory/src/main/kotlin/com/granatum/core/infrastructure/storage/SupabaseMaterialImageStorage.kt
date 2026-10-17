package com.granatum.core.infrastructure.storage

import com.granatum.core.domain.exception.ImageStorageUnavailableException
import com.granatum.core.domain.port.MaterialImageStorage
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
class SupabaseMaterialImageStorage(private val properties: MaterialImageStorageProperties) : MaterialImageStorage {

    private val base = properties.supabaseUrl.trimEnd('/') + "/storage/v1"

    private val client: RestClient = RestClient.builder()
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(properties.timeoutSeconds)).build()
            ).apply { setReadTimeout(Duration.ofSeconds(properties.timeoutSeconds)) }
        )
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${properties.key()}")
        .defaultHeader("apikey", properties.key())
        .build()

    override val available: Boolean = true

    override fun store(path: String, content: ByteArray, mediaType: String) {
        call {
            client.post()
                .uri("$base/object/${properties.bucket}/${safe(path)}")
                .contentType(MediaType.parseMediaType(mediaType))
                // Every upload has a new path (D-009): an existing object here
                // would be a bug, and failing beats silently replacing it.
                .header("x-upsert", "false")
                .body(content)
                .retrieve()
                .toBodilessEntity()
        }
    }

    override fun delete(paths: Collection<String>) {
        if (paths.isEmpty()) return
        call {
            client.method(HttpMethod.DELETE)
                .uri("$base/object/${properties.bucket}")
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("prefixes" to paths.map(::safe)))
                .retrieve()
                .toBodilessEntity()
        }
    }

    override fun sign(paths: Collection<String>): Map<String, String> {
        if (paths.isEmpty()) return emptyMap()
        val signed = call {
            client.post()
                .uri("$base/object/sign/${properties.bucket}")
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("expiresIn" to properties.urlSeconds, "paths" to paths.map(::safe)))
                .retrieve()
                .body(object : ParameterizedTypeReference<List<Map<String, Any?>>>() {})
        }.orEmpty()
        // signedURL comes relative to /storage/v1; a path Storage could not
        // sign carries an error and no URL, and is left out.
        return signed.mapNotNull { entry ->
            val path = entry["path"] as? String
            val url = entry["signedURL"] as? String
            if (path == null || url == null) null else path to base + url
        }.toMap()
    }

    /**
     * Paths are built by the service from UUIDs and an extension (D-009), so
     * this never fails in practice; it is what makes concatenating them into
     * the URL safe if that ever changes.
     */
    private fun safe(path: String): String {
        require(PATH.matches(path)) { "ruta de imagen no valida" }
        return path
    }

    private fun <T> call(request: () -> T): T =
        try {
            request()
        } catch (e: RestClientException) {
            throw ImageStorageUnavailableException(e)
        }

    private companion object {
        val PATH = Regex("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*\\.[a-z]+")
    }
}
