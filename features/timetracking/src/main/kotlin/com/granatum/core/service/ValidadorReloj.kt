package com.granatum.core.service

import com.granatum.core.domain.exception.DesviacionRelojException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Rejects operations whose device-declared time is implausible (FR-026 to
 * FR-026b).
 *
 * The tolerance is **asymmetric**, which is the whole point:
 *
 * - Into the future, five minutes. A clock-in dated later than now is always
 *   either a misconfigured device clock or an attempt at manipulation; there is
 *   no legitimate case for it.
 * - Into the past, 72 hours. A phone can genuinely spend a long weekend without
 *   coverage, and those shifts are real. A symmetric window would make the
 *   offline mode that US4 exists for unusable.
 *
 * 72 hours rather than a week is the deliberate stopping point: every extra day
 * is another day in which a compromised device could declare hours that were
 * never worked. A shift older than that is registered by a manager through the
 * correction flow, where it carries a reason and an approver.
 */
@Service
class ValidadorReloj(
    @param:Value("\${timetracking.reloj.tolerancia-futuro:PT5M}")
    private val toleranciaFuturo: Duration,

    @param:Value("\${timetracking.reloj.tolerancia-pasado:PT72H}")
    private val toleranciaPasado: Duration,

    private val clock: Clock = Clock.systemUTC()
) {

    /**
     * @throws DesviacionRelojException with its own error code, deliberately
     *   distinct from a plain validation failure, so the mobile app can tell the
     *   person their device clock is wrong instead of showing a generic
     *   rejection they cannot act on.
     */
    fun validar(occurredAt: Instant) {
        val ahora = clock.instant()

        val haciaElFuturo = Duration.between(ahora, occurredAt)
        if (haciaElFuturo > toleranciaFuturo) {
            throw DesviacionRelojException(
                "la hora declarada esta ${haciaElFuturo.toMinutes()} minutos en el futuro; " +
                    "el maximo admitido es ${toleranciaFuturo.toMinutes()}"
            )
        }

        val haciaElPasado = Duration.between(occurredAt, ahora)
        if (haciaElPasado > toleranciaPasado) {
            throw DesviacionRelojException(
                "la hora declarada esta ${haciaElPasado.toHours()} horas en el pasado; " +
                    "el maximo admitido es ${toleranciaPasado.toHours()}"
            )
        }
    }
}
