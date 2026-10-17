package com.granatum.core

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.web.client.RestClient
import kotlin.test.assertTrue

/**
 * `/actuator/info` reports the build's version (springBoot.buildInfo in
 * app/build.gradle.kts): it is how a deployment is checked against the tag it
 * was meant to run (docs/DESPLIEGUE.md).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InfoVersionIT {

    @LocalServerPort
    var port: Int = 0

    @Test
    fun `the info endpoint reports a SemVer version, without authentication`() {
        val body = RestClient.create("http://localhost:$port").get().uri("/actuator/info").retrieve().body(String::class.java)!!

        val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
        assertTrue(version != null && Regex("""\d+\.\d+\.\d+(-SNAPSHOT)?""").matches(version), body)
    }
}
