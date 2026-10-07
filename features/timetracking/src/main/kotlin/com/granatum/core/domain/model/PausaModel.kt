package com.granatum.core.domain.model

import com.granatum.core.domain.type.TipoPausa
import java.time.Instant
import java.util.UUID

data class PausaModel(
    val id: UUID,
    val tipo: TipoPausa,
    val inicio: Instant,
    val fin: Instant?
)
