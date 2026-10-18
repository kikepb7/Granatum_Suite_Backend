package com.granatum.core

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

/**
 * Test-only bootstrap for the sheets module, which has no `main()` of its own
 * (that lives in `app`). Carries @EnableJpaAuditing because the production entry
 * point does, like `InvoicesTestApplication`. No @EnableScheduling: the tests
 * drive the worker through `runOnce()`.
 */
@SpringBootApplication
@EnableJpaAuditing
class SheetsTestApplication
