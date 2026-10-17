package com.granatum.core

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

/**
 * Test-only bootstrap. The `inventory` module has no `main()` of its own
 * (that lives in `app`), but `@DataJpaTest` needs a `@SpringBootConfiguration`
 * somewhere on the test classpath to build its slice context.
 *
 * Carries @EnableJpaAuditing because the production entry point does: without
 * it the modification date would never move in these tests, and a test of
 * FR-007 would be testing a different application.
 */
@SpringBootApplication
@EnableJpaAuditing
class InventoryTestApplication
