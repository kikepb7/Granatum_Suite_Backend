package com.granatum.core

import com.granatum.core.api.controllers.DevAuthController
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.security.SecureRandom
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Enforces principle VI of the constitution: `POST /api/dev/token` must not
 * exist under the `prod` profile.
 *
 * That endpoint mints a JWT with whatever role the caller asks for, with no
 * credential at all. Reachable in production it is a complete impersonation of
 * ADMIN, and the only thing standing between it and the internet is a single
 * `@Profile("dev")` annotation - a one-line mistake away from being removed or
 * widened. So the guarantee is a test, not a convention.
 *
 * This boots a bare context rather than the full application so it needs no
 * database: the assertion is about whether Spring *registers the bean* for a
 * given profile, which is decided before any datasource is touched.
 *
 * Both directions are asserted on purpose. Checking only that `prod` excludes
 * the bean would still pass if someone deleted the controller outright, or
 * annotated it with a profile that never matches - the test would be green
 * while silently protecting nothing.
 */
/**
 * A fresh key per run. Local to this file rather than a shared test fixture:
 * four lines duplicated beats either adding the java-test-fixtures plugin or
 * shipping test helpers inside `common`'s production jar, and test source sets
 * are not shared across modules.
 *
 * Declared at file level because the nested @Configuration class below needs to
 * call it, and a nested (non-inner) class cannot reach the outer class members.
 */
private fun randomTestJwtKeyBase64(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getEncoder().encodeToString(bytes)
}

class DevAuthControllerProfileTest {

    @Configuration
    class JwtServiceStub {
        // The real class with a key generated per run; the test never mints a
        // token, it only needs the dependency to be satisfiable under `dev`.
        @Bean
        fun jwtService() = JwtService(
            secretBase64 = randomTestJwtKeyBase64(),
            expirationMinutes = 15
        )
    }

    private fun devAuthControllerBeanCount(vararg profiles: String): Int =
        AnnotationConfigApplicationContext().use { context ->
            context.environment.setActiveProfiles(*profiles)
            context.register(JwtServiceStub::class.java, DevAuthController::class.java)
            context.refresh()
            context.getBeanNamesForType(DevAuthController::class.java).size
        }

    @Test
    fun `the dev token endpoint is not registered under the prod profile`() {
        assertEquals(
            0,
            devAuthControllerBeanCount("prod"),
            "DevAuthController must not be registered with the prod profile active. " +
                "It issues a JWT with any requested role and no credentials, so in " +
                "production it is a full ADMIN impersonation."
        )
    }

    @Test
    fun `the dev token endpoint is still registered under the dev profile`() {
        assertEquals(
            1,
            devAuthControllerBeanCount("dev"),
            "DevAuthController should remain available in dev - without this the " +
                "prod assertion above could pass for the wrong reason."
        )
    }

    @Test
    fun `the controller is gated on an explicit profile allowlist`() {
        // A deny-list such as @Profile("!prod") would leave the endpoint live
        // under any future profile name (staging, qa, demo...). Assert the gate
        // is a positive match on `dev` and nothing else.
        val profileAnnotation = DevAuthController::class.java
            .getAnnotation(org.springframework.context.annotation.Profile::class.java)

        assertTrue(
            profileAnnotation != null,
            "DevAuthController must carry an explicit @Profile annotation"
        )
        assertEquals(
            listOf("dev"),
            profileAnnotation.value.toList(),
            "The gate must be an allowlist of exactly [dev]; a negated profile " +
                "like !prod would expose the endpoint under staging, qa, demo, etc."
        )
        assertFalse(
            profileAnnotation.value.any { it.startsWith("!") },
            "A negated profile expression is not an acceptable gate here"
        )
    }
}
