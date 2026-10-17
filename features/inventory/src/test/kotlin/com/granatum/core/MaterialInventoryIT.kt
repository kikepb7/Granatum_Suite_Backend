package com.granatum.core

import com.granatum.core.domain.contract.InsufficientStockException
import com.granatum.core.domain.contract.MaterialInventory
import com.granatum.core.domain.contract.MaterialMissingException
import com.granatum.core.domain.exception.MaterialInUseException
import com.granatum.core.domain.type.MaterialChangeType
import com.granatum.core.domain.type.Warehouse
import com.granatum.core.infrastructure.database.entities.CategoryEntity
import com.granatum.core.infrastructure.database.entities.MaterialDimensionsEmbeddable
import com.granatum.core.infrastructure.database.entities.MaterialEntity
import com.granatum.core.infrastructure.database.entities.MaterialLocationEmbeddable
import com.granatum.core.infrastructure.database.repositories.CategoryRepository
import com.granatum.core.infrastructure.database.repositories.MaterialHistoryRepository
import com.granatum.core.infrastructure.database.repositories.MaterialRepository
import com.granatum.core.service.MaterialService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The inventory side of `MaterialInventory` (feature 011) against a real Postgres: withdrawals
 * are atomic, the stock never goes negative and every movement lands in the history.
 *
 * Not `@Transactional`: the concurrency test needs each withdrawal in its own transaction,
 * committed for real, as it would be in production.
 */
@Testcontainers
@SpringBootTest(classes = [InventoryTestApplication::class])
class MaterialInventoryIT {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("spring.flyway.enabled") { "true" }
        }
    }

    @Autowired lateinit var sut: MaterialInventory
    @Autowired lateinit var categoryRepository: CategoryRepository
    @Autowired lateinit var materialRepository: MaterialRepository
    @Autowired lateinit var historyRepository: MaterialHistoryRepository
    @Autowired lateinit var materialService: MaterialService
    @Autowired lateinit var materialUsage: MaterialUsageFake
    @Autowired lateinit var transactions: TransactionTemplate

    private val user = UUID.randomUUID()

    private fun material(quantity: Int): UUID {
        val category = categoryRepository.save(CategoryEntity(name = "Jarrones ${UUID.randomUUID()}", description = null))
        return materialRepository.save(
            MaterialEntity(
                category = category,
                description = "Jarrón cilíndrico",
                dimensions = MaterialDimensionsEmbeddable(height = BigDecimal("40.00"), width = BigDecimal("12.00")),
                quantity = quantity,
                location = MaterialLocationEmbeddable(warehouse = Warehouse.A, section = 2)
            )
        ).id
    }

    private fun quantity(id: UUID) = materialRepository.quantityOf(id)

    @Test
    fun `When units are withdrawn then the stock drops and the history names the reason`() {
        val id = material(quantity = 8)

        val actual = transactions.execute { sut.withdraw(id, 5, user, "Reservado para el evento «Evento 1»") }!!

        assertEquals(expected = 3, actual = actual.quantity)
        assertEquals(expected = 3, actual = quantity(id))
        val entry = historyRepository.findAllByMaterialIdOrderByDateDesc(id).single()
        assertEquals(expected = MaterialChangeType.QUANTITY, actual = entry.changeType)
        assertEquals(expected = "8" to "3", actual = entry.previousValue to entry.newValue)
        assertEquals(expected = "Reservado para el evento «Evento 1»", actual = entry.reason)
        assertEquals(expected = user, actual = entry.userId)
    }

    @Test
    fun `When there are not enough units then nothing changes and the available amount is reported`() {
        val id = material(quantity = 3)

        val actual = assertFailsWith<InsufficientStockException> { transactions.execute { sut.withdraw(id, 4, user, "x") } }

        assertEquals(expected = 3, actual = actual.available)
        assertEquals(expected = 3, actual = quantity(id))
        assertTrue(historyRepository.findAllByMaterialIdOrderByDateDesc(id).isEmpty())
    }

    @Test
    fun `When the material does not exist then withdrawing fails and returning is a no-op`() {
        val missing = UUID.randomUUID()

        assertFailsWith<MaterialMissingException> { transactions.execute { sut.withdraw(missing, 1, user, "x") } }
        assertFalse(transactions.execute { sut.putBack(missing, 1, user, "x") }!!)
    }

    @Test
    fun `When units are returned then the stock grows and the history records it`() {
        val id = material(quantity = 2)

        val actual = transactions.execute { sut.putBack(id, 5, user, "Devuelto del evento «Evento 1»") }!!

        assertTrue(actual)
        assertEquals(expected = 7, actual = quantity(id))
        assertEquals(
            expected = "2" to "7",
            actual = historyRepository.findAllByMaterialIdOrderByDateDesc(id).single().let { it.previousValue to it.newValue }
        )
    }

    @Test
    fun `When many withdrawals race for the last units then the stock never goes negative`() {
        val id = material(quantity = 10)
        val attempts = 25
        val successes = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)

        repeat(attempts) {
            pool.submit {
                start.await()
                runCatching { transactions.execute { sut.withdraw(id, 1, user, "Carrera") } }
                    .onSuccess { successes.incrementAndGet() }
            }
        }
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)

        assertEquals(expected = 10, actual = successes.get())
        assertEquals(expected = 0, actual = quantity(id))
    }

    @Test
    fun `When a material is out at an event then the inventory refuses to delete it`() {
        val id = material(quantity = 1)
        materialUsage.inUse += id

        assertFailsWith<MaterialInUseException> { materialService.delete(id) }

        assertEquals(expected = 1, actual = quantity(id))
        materialUsage.inUse -= id
    }

    @Test
    fun `When listing for an event then every material comes with its free units and location`() {
        val id = material(quantity = 4)

        val actual = sut.available().single { it.id == id }

        assertEquals(expected = 4, actual = actual.quantity)
        assertEquals(expected = "A" to 2, actual = actual.warehouse to actual.section)
        assertEquals(expected = setOf(id), actual = sut.summarize(listOf(id, UUID.randomUUID())).keys)
    }
}
