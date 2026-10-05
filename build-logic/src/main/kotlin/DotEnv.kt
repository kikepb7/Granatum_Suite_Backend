import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * Reads the project's `.env` file so `bootRun` and the test tasks see it as
 * real environment variables.
 *
 * This exists because nothing was loading it. Spring Boot does not read `.env`
 * natively and neither does Gradle, so the README's "copy .env.example to .env"
 * had no effect: the application only worked locally because
 * `application.yml` carried hard-coded fallbacks for the JWT signing key and
 * the database password. Those fallbacks were therefore load-bearing, and the
 * signing key one was dangerous - with `JWT_SECRET_BASE64` unset, production
 * would have booted with a key committed to the repository, which is enough for
 * anyone holding a clone to forge an ADMIN token.
 *
 * Deliberately a development-only convenience. In production the variables come
 * from the platform, and `application.yml` now has no fallback at all, so a
 * missing secret stops the application at startup instead of silently using a
 * public one.
 */
fun loadDotEnv(projectDir: File): Map<String, String> {
    val file = File(projectDir, ".env")
    if (!file.exists()) return emptyMap()

    return file.readLines()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .associate { line ->
            val separator = line.indexOf('=')
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim().removeSurrounding("\"")
            key to value
        }
}

/**
 * A fresh 256-bit base64 key, generated per build.
 *
 * Tests never need a stable signing key - they only round-trip tokens - so
 * generating one here means no key has to be committed anywhere, not even a
 * throwaway. A fixed "test" key in the repository is the thing that ends up
 * pasted into a real deployment.
 */
fun randomJwtKeyBase64(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getEncoder().encodeToString(bytes)
}
