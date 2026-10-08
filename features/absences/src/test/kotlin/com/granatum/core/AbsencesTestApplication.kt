package com.granatum.core

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

/** Test-only boot class for this module's Spring contexts (feature 007). */
@SpringBootApplication
@EnableJpaAuditing
class AbsencesTestApplication
