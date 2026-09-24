package com.granatum.core

import org.springframework.boot.autoconfigure.SpringBootApplication

/**
 * Test-only bootstrap. The `inventory` module has no `main()` of its own
 * (that lives in `app`), but `@DataJpaTest` needs a `@SpringBootConfiguration`
 * somewhere on the test classpath to build its slice context.
 */
@SpringBootApplication
class InventoryTestApplication
