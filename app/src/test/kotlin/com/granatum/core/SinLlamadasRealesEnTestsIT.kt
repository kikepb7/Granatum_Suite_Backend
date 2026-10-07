package com.granatum.core

import com.granatum.core.domain.port.ReconocedorFacturas
import com.granatum.core.infrastructure.claude.ReconocedorDeshabilitado
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertIs

/**
 * No test of app can call the real Claude API (specs/004-invoices, finding S2).
 *
 * Gradle loads .env into every test task (DotEnv.kt), so an ANTHROPIC_API_KEY
 * a developer puts there for local use would reach the suite, and every build
 * would spend money. Here the variable is defined to a fake value, as if it
 * were in .env, and the context must still build the disabled recogniser:
 * `src/test/resources/config/application.yml` pins the only property the real
 * client is built from.
 *
 * Validated by mutation (2026-10-08): with that file removed, the context
 * built `ReconocedorClaude` from the fake key and this test went red; restored.
 */
@SpringBootTest(properties = ["ANTHROPIC_API_KEY=sk-ant-clave-falsa-de-prueba"])
class SinLlamadasRealesEnTestsIT {

    @Autowired
    lateinit var reconocedor: ReconocedorFacturas

    @Test
    fun `even with ANTHROPIC_API_KEY defined, the test context never builds the real client`() {
        assertIs<ReconocedorDeshabilitado>(reconocedor)
    }
}
