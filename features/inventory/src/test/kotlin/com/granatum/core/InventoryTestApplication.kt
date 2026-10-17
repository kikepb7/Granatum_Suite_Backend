package com.granatum.core

import com.granatum.core.domain.contract.UsoMateriales
import com.granatum.core.domain.type.EntityId
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
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
class InventoryTestApplication {

    /**
     * Feature 011: in production `events` implements [UsoMateriales]; this module must not depend
     * on it even in tests, so here it is a double that tests can switch.
     */
    @Bean
    fun usoMateriales() = UsoMaterialesDoble()
}

/** No material is in use unless a test says so. */
class UsoMaterialesDoble : UsoMateriales {
    val enUso = mutableSetOf<EntityId>()

    override fun enUso(materialId: EntityId): Boolean = materialId in enUso
}
