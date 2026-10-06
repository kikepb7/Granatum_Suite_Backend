package com.granatum.core

import com.granatum.core.service.AutenticacionService
import org.junit.jupiter.api.Test
import java.io.File
import java.util.jar.JarFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No class of the `auth` module exists twice on the application's classpath.
 *
 * ## The failure this guards
 *
 * Every module shares the `com.granatum.core` namespace by decision of the
 * constitution, and each is packaged into its own jar. Two classes with the same
 * qualified name mean **only one is loaded**: `auth` compiles against its own,
 * the build passes, and at runtime the other module's is resolved instead.
 *
 * It nearly happened. `timetracking` already declares `EmpleadoInactivoException`
 * (extends `InvalidOperationException`, HTTP **400**) in
 * `com.granatum.core.domain.exception`; `auth` needed the same idea with HTTP
 * **401**. Under one name, a refusal for dismissal would have answered
 * `400 INVALID_OPERATION` - or thrown `NoSuchMethodError` - with every test in
 * every module green, because each module only has its own class on its own
 * classpath. That is why the class is called `CuentaDeEmpleadoInactivoException`.
 *
 * ## Why here, and why a test rather than a naming rule
 *
 * Only `app` has every module on one classpath, so only here can a collision be
 * seen at all. And a naming convention with no test is an intention (principle
 * V): the next collision would be just as invisible as this one.
 *
 * Not a Spring test: it only needs the classpath.
 */
class SinColisionDeClasesIT {

    private val cargador: ClassLoader = Thread.currentThread().contextClassLoader

    /** Every `.class` resource the `auth` module ships, as classpath paths. */
    private fun clasesDeAuth(): List<String> {
        val origen = AutenticacionService::class.java.protectionDomain.codeSource.location.toURI()
        val raiz = File(origen)

        return if (raiz.isDirectory) {
            raiz.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") }
                .map { it.relativeTo(raiz).invariantSeparatorsPath }
                .toList()
        } else {
            JarFile(raiz).use { jar ->
                jar.entries().asSequence()
                    .map { it.name }
                    .filter { it.endsWith(".class") && !it.startsWith("META-INF/") }
                    .toList()
            }
        }
    }

    @Test
    fun `the module actually contributes classes`() {
        // Guards the guard: if the lookup above silently found nothing, the
        // assertion below would pass over an empty list and prove nothing.
        assertTrue(
            clasesDeAuth().any { it.endsWith("AutenticacionService.class") },
            "the scan must find auth's own classes, or the collision check is vacuous"
        )
    }

    @Test
    fun `no class of auth is declared by any other module`() {
        val colisiones = clasesDeAuth()
            .associateWith { ruta -> cargador.getResources(ruta).toList() }
            .filterValues { it.size > 1 }
            .map { (ruta, urls) -> "$ruta -> ${urls.map(::origenLegible)}" }

        assertEquals(
            emptyList(),
            colisiones,
            "two modules declare the same class: only one will be loaded at runtime, and " +
                "the module that compiled against the other gets the wrong behaviour with " +
                "every test green. Rename one of them."
        )
    }

    private fun origenLegible(url: java.net.URL): String =
        url.toString().substringBefore("!/").substringAfterLast("/build/").ifEmpty { url.toString() }
}
