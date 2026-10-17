package com.granatum.core

import com.granatum.core.service.AbsenceService
import com.granatum.core.service.AuthenticationService
import com.granatum.core.service.EventService
import com.granatum.core.service.InvoiceUploads
import com.granatum.core.service.MaterialService
import com.granatum.core.service.NotificationService
import com.granatum.core.service.ShiftService
import org.junit.jupiter.api.Test
import java.io.File
import java.util.jar.JarFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No class of a feature module exists twice on the application's classpath.
 *
 * ## The failure this guards
 *
 * Every module shares the `com.granatum.core` namespace by decision of the
 * constitution, and each is packaged into its own jar. Two classes with the same
 * qualified name mean **only one is loaded**: `auth` compiles against its own,
 * the build passes, and at runtime the other module's is resolved instead.
 *
 * It nearly happened. `timetracking` already declares `EmployeeInactiveException`
 * (extends `InvalidOperationException`, HTTP **400**) in
 * `com.granatum.core.domain.exception`; `auth` needed the same idea with HTTP
 * **401**. Under one name, a refusal for dismissal would have answered
 * `400 INVALID_OPERATION` - or thrown `NoSuchMethodError` - with every test in
 * every module green, because each module only has its own class on its own
 * classpath. That is why the class is called `InactiveEmployeeAccountException`.
 *
 * ## Why here, and why a test rather than a naming rule
 *
 * Only `app` has every module on one classpath, so only here can a collision be
 * seen at all. And a naming convention with no test is an intention (principle
 * V): the next collision would be just as invisible as this one.
 *
 * Not a Spring test: it only needs the classpath.
 */
class NoClassNameCollisionIT {

    private val loader: ClassLoader = Thread.currentThread().contextClassLoader

    /**
     * One class of each feature module, to find its jar or classes directory.
     * Feature 004 widened the check from `auth` alone to every feature: the
     * invoicing module brought dozens of new names into the shared namespace.
     */
    private val modules = mapOf(
        "inventory" to MaterialService::class.java,
        "timetracking" to ShiftService::class.java,
        "auth" to AuthenticationService::class.java,
        "invoices" to InvoiceUploads::class.java,
        "absences" to AbsenceService::class.java,
        "notifications" to NotificationService::class.java,
        "events" to EventService::class.java
    )

    /** Every `.class` resource a module ships, as classpath paths. */
    private fun classesOf(anchor: Class<*>): List<String> {
        val root = File(anchor.protectionDomain.codeSource.location.toURI())

        return if (root.isDirectory) {
            root.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") }
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .toList()
        } else {
            JarFile(root).use { jar ->
                jar.entries().asSequence()
                    .map { it.name }
                    .filter { it.endsWith(".class") && !it.startsWith("META-INF/") }
                    .toList()
            }
        }
    }

    @Test
    fun `every module actually contributes classes`() {
        // Guards the guard: if a lookup silently found nothing, the assertion
        // below would pass over an empty list and prove nothing.
        modules.forEach { (name, anchor) ->
            assertTrue(
                classesOf(anchor).any { it.endsWith("${anchor.simpleName}.class") },
                "the scan must find $name's own classes, or the collision check is vacuous"
            )
        }
    }

    @Test
    fun `no class of a feature module is declared by any other module`() {
        val collisions = modules.values.flatMap(::classesOf).distinct()
            .associateWith { path -> loader.getResources(path).toList() }
            .filterValues { it.size > 1 }
            .map { (path, urls) -> "$path -> ${urls.map(::readableOrigin)}" }

        assertEquals(
            emptyList(),
            collisions,
            "two modules declare the same class: only one will be loaded at runtime, and " +
                "the module that compiled against the other gets the wrong behaviour with " +
                "every test green. Rename one of them."
        )
    }

    private fun readableOrigin(url: java.net.URL): String =
        url.toString().substringBefore("!/").substringAfterLast("/build/").ifEmpty { url.toString() }
}
