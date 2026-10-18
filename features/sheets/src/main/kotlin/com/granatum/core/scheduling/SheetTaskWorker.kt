package com.granatum.core.scheduling

import com.granatum.core.domain.model.SheetTask
import com.granatum.core.domain.model.SheetTaskErrorReason
import com.granatum.core.domain.model.SheetTaskKind
import com.granatum.core.domain.port.SheetsPermanentException
import com.granatum.core.domain.port.SheetsTransientException
import com.granatum.core.domain.port.SheetsUnavailableException
import com.granatum.core.service.SheetTaskAbortedException
import com.granatum.core.service.SheetTaskHandler
import com.granatum.core.service.SheetTaskLedger
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Processes the queue of writes (research.md D-003).
 *
 * Each pass:
 * 1. puts back RUNNING tasks untouched for [STALE_AFTER] (a server died
 *    half-way) and purges DONE tasks older than [KEEP_DONE];
 * 2. claims due tasks in a short transaction (`FOR UPDATE SKIP LOCKED`, so two
 *    workers never take the same one);
 * 3. hands each to the [SheetTaskHandler] of its kind **outside any
 *    transaction** - the call to Google may take seconds and must not hold a
 *    database connection;
 * 4. records the result in another short transaction.
 *
 * A kind with no handler ends FAILED with a clear reason, rather than
 * waiting for ever.
 */
@Component
class SheetTaskWorker(
    handlers: List<SheetTaskHandler>,
    private val ledger: SheetTaskLedger,
    @param:Value("\${sheets.max-attempts:8}") private val maxAttempts: Int = 8,
    /** Off in the controller tests: a background pass would change tasks under their assertions. */
    @param:Value("\${sheets.worker.immediate:true}") private val immediate: Boolean = true,
    @param:Value("\${sheets.worker.batch-size:10}") private val batchSize: Int = 10,
    private val clock: Clock = Clock.systemUTC()
) {
    companion object {
        val STALE_AFTER: Duration = Duration.ofMinutes(10)
        val KEEP_DONE: Duration = Duration.ofDays(90)

        /** A guard against a task that keeps asking to run again: the rest waits for the next pass. */
        const val MAX_TASKS_PER_PASS = 100
    }

    private val log = LoggerFactory.getLogger(javaClass)

    private val handlers: Map<SheetTaskKind, SheetTaskHandler> = handlers
        .groupBy { it.kind }
        .mapValues { (kind, found) ->
            check(found.size == 1) { "Hay ${found.size} escritores para las tareas $kind; debe haber uno" }
            found.single()
        }

    private val kicker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sheets-worker-kick").apply { isDaemon = true }
    }
    private val kickQueued = AtomicBoolean(false)

    @Scheduled(cron = "\${sheets.worker.cron:*/30 * * * * *}", zone = "Europe/Madrid")
    fun scheduled() {
        runOnce()
    }

    /**
     * Asks for a pass now, on the worker's own thread, without waiting for it.
     * Call it after the transaction that queued the task has committed. Kicks
     * while a pass is already queued are merged into it.
     */
    fun kick() {
        if (!immediate) return
        if (kickQueued.compareAndSet(false, true)) {
            kicker.execute {
                kickQueued.set(false)
                try {
                    runOnce()
                } catch (e: Exception) {
                    log.warn("Fallo en la pasada inmediata de Google Sheets: {}", e.javaClass.simpleName)
                }
            }
        }
    }

    /** One pass over the queue. Public so tests drive it. Returns how many tasks it ran. */
    fun runOnce(): Int {
        val now = clock.instant()
        ledger.requeueStale(now.minus(STALE_AFTER), now)
        ledger.purgeDone(now.minus(KEEP_DONE))

        var ran = 0
        while (ran < MAX_TASKS_PER_PASS) {
            val claimed = ledger.claim(minOf(batchSize, MAX_TASKS_PER_PASS - ran), clock.instant())
            if (claimed.isEmpty()) break
            claimed.forEach { execute(it) }
            ran += claimed.size
        }
        return ran
    }

    private fun execute(task: SheetTask) {
        val handler = handlers[task.kind]
        if (handler == null) {
            ledger.fail(
                task.id, "No hay ningún escritor para las tareas de tipo ${task.kind}",
                SheetTaskErrorReason.NO_HANDLER, maxAttempts, clock.instant()
            )
            return
        }
        try {
            val result = handler.handle(task)
            ledger.succeed(task.id, result, clock.instant())
        } catch (e: SheetsPermanentException) {
            fail(task, e.message, SheetTaskErrorReason.of(e.reason))
        } catch (e: SheetsUnavailableException) {
            fail(task, e.message, SheetTaskErrorReason.UNAVAILABLE)
        } catch (e: SheetTaskAbortedException) {
            fail(task, e.message, SheetTaskErrorReason.ABORTED)
        } catch (e: SheetsTransientException) {
            fail(task, e.message, SheetTaskErrorReason.TRANSIENT)
        } catch (e: Exception) {
            // Only the class: a message could quote data of the row.
            log.warn("Error inesperado en la tarea {} de Google Sheets: {}", task.id, e.javaClass.name)
            fail(task, "Error inesperado al escribir en la hoja; se reintentará", SheetTaskErrorReason.TRANSIENT)
        }
    }

    private fun fail(task: SheetTask, message: String?, reason: SheetTaskErrorReason) {
        val text = message ?: "Error al escribir en la hoja"
        log.info("Tarea {} de Google Sheets ({}) sin completar ({}): {}", task.id, task.kind, reason, text)
        ledger.fail(task.id, text, reason, maxAttempts, clock.instant())
    }

    @PreDestroy
    fun stop() {
        kicker.shutdownNow()
    }
}
