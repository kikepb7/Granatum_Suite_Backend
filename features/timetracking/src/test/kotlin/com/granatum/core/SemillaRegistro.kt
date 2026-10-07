package com.granatum.core

import com.granatum.core.api.util.RangoFechas
import com.granatum.core.domain.service.CalculadoraJornada
import com.granatum.core.domain.type.EstadoFichaje
import com.granatum.core.domain.type.TipoContrato
import com.granatum.core.domain.type.TipoPausa
import com.granatum.core.infrastructure.database.entities.EmpleadoEntity
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * Seeds the register with fixed dates for the export tests.
 *
 * Through JDBC for the reason [RetencionIT] gives: the service refuses
 * timestamps outside the clock tolerance, and an export test needs past
 * months, daylight-saving days and shifts years old. Worked minutes come from
 * [CalculadoraJornada], the same function the service uses, so a seeded shift
 * is one the service could have produced.
 *
 * Times are civil times in `Europe/Madrid`, as the tests read them.
 */
class SemillaRegistro(
    private val dataSource: DataSource,
    private val empleados: EmpleadoRepository
) {
    data class Pausa(val tipo: TipoPausa, val inicio: String, val fin: String?)

    fun empleado(
        nombre: String = "Persona de prueba",
        tipo: TipoContrato = TipoContrato.JORNADA_COMPLETA,
        activo: Boolean = true,
        documento: String = "T${UUID.randomUUID().toString().take(8).uppercase()}"
    ): EmpleadoEntity =
        empleados.save(
            EmpleadoEntity(
                nombre = nombre,
                documentoIdentidad = documento,
                puesto = "Florista",
                tipoContrato = tipo,
                fechaAlta = LocalDate.parse("2020-01-01"),
                activo = activo
            )
        )

    fun instante(fechaHora: String): Instant =
        LocalDateTime.parse(fechaHora.replace(' ', 'T')).atZone(RangoFechas.ZONA).toInstant()

    /**
     * A shift. [salida] null leaves it open (`EN_CURSO`) unless [estado] says
     * otherwise; minutes are only computed for a closed one.
     */
    fun fichaje(
        empleado: EmpleadoEntity,
        entrada: String,
        salida: String?,
        pausas: List<Pausa> = emptyList(),
        estado: EstadoFichaje = if (salida == null) EstadoFichaje.EN_CURSO else EstadoFichaje.CERRADO,
        fueIncompleto: Boolean = false,
        ubicacion: Pair<String, String>? = null
    ): UUID {
        val id = UUID.randomUUID()
        val minutos = if (estado == EstadoFichaje.CERRADO && salida != null) {
            CalculadoraJornada.minutosTrabajados(
                instante(entrada),
                instante(salida),
                pausas.map { CalculadoraJornada.IntervaloPausa(instante(it.inicio), instante(it.fin!!)) }
            )
        } else {
            null
        }
        dataSource.connection.use { c ->
            c.prepareStatement(
                """
                INSERT INTO fichajes
                    (id, empleado_id, entrada, salida, estado, minutos_trabajados, fue_incompleto,
                     ubicacion_entrada_latitud, ubicacion_entrada_longitud, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::numeric, ?::numeric, now(), now())
                """.trimIndent()
            ).use { s ->
                s.setObject(1, id)
                s.setObject(2, empleado.id)
                s.setTimestamp(3, Timestamp.from(instante(entrada)))
                s.setTimestamp(4, salida?.let { Timestamp.from(instante(it)) })
                s.setString(5, estado.name)
                s.setObject(6, minutos)
                s.setBoolean(7, fueIncompleto)
                s.setString(8, ubicacion?.first)
                s.setString(9, ubicacion?.second)
                s.executeUpdate()
            }
            pausas.forEach { p ->
                c.prepareStatement(
                    "INSERT INTO pausas (id, fichaje_id, tipo, inicio, fin) VALUES (?, ?, ?, ?, ?)"
                ).use { s ->
                    s.setObject(1, UUID.randomUUID())
                    s.setObject(2, id)
                    s.setString(3, p.tipo.name)
                    s.setTimestamp(4, Timestamp.from(instante(p.inicio)))
                    s.setTimestamp(5, p.fin?.let { Timestamp.from(instante(it)) })
                    s.executeUpdate()
                }
            }
        }
        return id
    }

    /**
     * Every row and every `updated_at` of the register, so a test can show that
     * exporting changed nothing (FR-018).
     */
    fun huellaDelRegistro(): Map<String, String> =
        dataSource.connection.use { c ->
            listOf(
                "empleados" to "SELECT count(*), max(updated_at) FROM empleados",
                "fichajes" to "SELECT count(*), max(updated_at) FROM fichajes",
                "pausas" to "SELECT count(*), max(coalesce(fin, inicio)) FROM pausas",
                "solicitudes" to "SELECT count(*), max(coalesce(resuelta_en, created_at)) FROM solicitudes_correccion_fichaje",
                "eventos" to "SELECT count(*), max(received_at) FROM fichaje_eventos"
            ).associate { (tabla, sql) ->
                c.createStatement().use { s ->
                    s.executeQuery(sql).use { r ->
                        r.next()
                        tabla to "${r.getLong(1)}/${r.getString(2)}"
                    }
                }
            }
        }
}
