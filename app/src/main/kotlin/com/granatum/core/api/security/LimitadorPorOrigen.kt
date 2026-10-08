package com.granatum.core.api.security

import java.time.Clock
import java.time.Duration

/** The quotas of feature 006 (research.md D-003). */
enum class Cupo {
    /** `POST /api/auth/login`. */
    LOGIN,

    /** `POST /api/auth/refresh` and `POST /api/auth/logout`. */
    SESION,

    /** `POST /api/auth/registro`, bootstrap included. */
    REGISTRO,

    /** Everything under `/api`. */
    GENERAL
}

/**
 * Per-origin request quotas, in memory (feature 006, research.md D-001).
 *
 * One bounded LRU map per quota: when it is full, the least recently used
 * address is forgotten, so inventing addresses cannot grow the heap (FR-006).
 * A forgotten address starts again with a full bucket - acceptable, because the
 * limit slows a rate; it is not a record.
 *
 * ## Personal data
 *
 * The address is personal data. It only exists here, as a map key, for as long
 * as it is in the map; nothing in this class or its filter logs it, and nothing
 * stores it (FR-006, principle VI).
 *
 * ## One instance
 *
 * Counters are per process. With several instances the effective limit is the
 * configured one times the number of instances; shared counters would need
 * infrastructure the constitution keeps out until a spec demonstrates the need.
 */
class LimitadorPorOrigen(
    cupos: Map<Cupo, Pair<Int, Duration>>,
    private val maxDirecciones: Int,
    private val reloj: Clock = Clock.systemUTC()
) {

    private class Registro(val capacidad: Int, val periodo: Duration, val cubos: LinkedHashMap<String, CuboFichas>)

    private val registros: Map<Cupo, Registro> = cupos.mapValues { (_, cupo) ->
        val (capacidad, periodo) = cupo
        val maximo = maxDirecciones
        Registro(
            capacidad,
            periodo,
            // accessOrder = true: iteration order is least recently used first,
            // which is what removeEldestEntry evicts.
            object : LinkedHashMap<String, CuboFichas>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CuboFichas>?): Boolean =
                    size > maximo
            }
        )
    }

    /**
     * Takes one request from [direccion]'s bucket for [cupo]. `null` if allowed;
     * otherwise the seconds to wait. A quota that is not configured does not
     * limit.
     */
    fun consumir(cupo: Cupo, direccion: String): Long? {
        val registro = registros[cupo] ?: return null
        val ahora = reloj.instant()
        synchronized(registro.cubos) {
            val cubo = registro.cubos.getOrPut(direccion) { CuboFichas(registro.capacidad, registro.periodo, ahora) }
            return cubo.consumir(ahora)
        }
    }

    /** For tests: how many addresses the quota currently remembers. */
    fun direccionesRecordadas(cupo: Cupo): Int =
        registros[cupo]?.let { synchronized(it.cubos) { it.cubos.size } } ?: 0
}
