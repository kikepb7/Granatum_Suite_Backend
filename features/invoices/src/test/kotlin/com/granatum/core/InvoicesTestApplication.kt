package com.granatum.core

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

/**
 * Test-only bootstrap for the invoices module, which has no `main()` of its own
 * (that lives in `app`). Carries @EnableJpaAuditing because the production entry
 * point does, like `TimetrackingTestApplication`.
 */
@SpringBootApplication
@EnableJpaAuditing
class InvoicesTestApplication
