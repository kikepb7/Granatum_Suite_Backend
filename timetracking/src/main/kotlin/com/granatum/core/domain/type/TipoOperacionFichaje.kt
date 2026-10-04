package com.granatum.core.domain.type

/** The four operations recorded in the append-only event log. */
enum class TipoOperacionFichaje {
    ENTRADA,
    INICIO_PAUSA,
    FIN_PAUSA,
    SALIDA
}
