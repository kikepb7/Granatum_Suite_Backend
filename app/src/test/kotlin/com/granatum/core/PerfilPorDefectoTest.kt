package com.granatum.core

import com.granatum.core.api.controllers.DevAuthController
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import kotlin.test.assertEquals

/**
 * Feature 006, FR-007, SC-003: a deployment that sets no profile runs as `prod`.
 *
 * Until this feature the default was `dev`, so forgetting SPRING_PROFILES_ACTIVE
 * left `POST /api/dev/token` - a token for any role, no credential - live in
 * production. DevAuthControllerProfileTest proves the endpoint is absent under
 * `prod`; this proves `prod` is what you get when nobody says anything.
 *
 * The environment is built from application.yml alone, with neither system
 * environment nor system properties: the test JVM receives `.env`, which sets
 * `dev` for local work, and that must not decide what this test sees.
 */
class PerfilPorDefectoTest {

    private fun perfilSinConfigurar(): String {
        val entorno = StandardEnvironment()
        entorno.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        entorno.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
        YamlPropertySourceLoader().load("application", ClassPathResource("application.yml"))
            .forEach { entorno.propertySources.addLast(it) }
        return entorno.getRequiredProperty("spring.profiles.active")
    }

    @Test
    fun `with no SPRING_PROFILES_ACTIVE the active profile is prod`() {
        assertEquals("prod", perfilSinConfigurar())
    }

    @Test
    fun `and under that default the development token emitter does not exist`() {
        val perfil = perfilSinConfigurar()

        val beans = AnnotationConfigApplicationContext().use { context ->
            context.environment.setActiveProfiles(perfil)
            context.register(DevAuthControllerProfileTest.JwtServiceStub::class.java, DevAuthController::class.java)
            context.refresh()
            context.getBeanNamesForType(DevAuthController::class.java).size
        }

        assertEquals(0, beans)
    }
}
