package com.granatum.core

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

/**
 * Test-only bootstrap. The `auth` module has no `main()` of its own (that lives
 * in `app`), but the integration tests need a `@SpringBootConfiguration`
 * somewhere on the test classpath to build a context.
 *
 * Carries @EnableJpaAuditing because the production entry point does: without
 * it the @CreatedDate / @LastModifiedDate fields are never populated here, and
 * the tests would exercise a context that behaves differently from the running
 * application. That is not hypothetical - it is exactly the bug `timetracking`
 * hit, where `created_at` came back null only under test.
 */
@SpringBootApplication
@EnableJpaAuditing
class AuthTestApplication
