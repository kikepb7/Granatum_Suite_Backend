package com.granatum.core

import org.springframework.boot.autoconfigure.SpringBootApplication

/**
 * Test-only bootstrap. The `timetracking` module has no `main()` of its own
 * (that lives in `app`), but the integration tests need a
 * `@SpringBootConfiguration` somewhere on the test classpath to build a
 * context.
 */
@SpringBootApplication
class TimetrackingTestApplication
