package com.granatum.core.service

import org.springframework.stereotype.Component
import java.util.UUID

/**
 * A point inside a confirmation, right after its checks and before it writes.
 * In production it does nothing. It exists so that concurrency tests can make
 * two operations meet exactly there and show what the locks and the unique
 * indexes prevent, instead of hoping the scheduler produces the race
 * (DuplicadoLogicoIT; finding T1 of the analysis).
 */
interface SondaConcurrencia {
    fun trasComprobar(facturaId: UUID)
}

@Component
class SondaConcurrenciaNula : SondaConcurrencia {
    override fun trasComprobar(facturaId: UUID) = Unit
}
