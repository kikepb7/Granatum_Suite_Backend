package com.granatum.core.service

import com.granatum.core.api.util.RangoFechas
import com.granatum.core.domain.exception.EmpleadoNotFoundException
import com.granatum.core.domain.exception.ExportacionSaturadaException
import com.granatum.core.domain.exception.ForbiddenException
import com.granatum.core.domain.exception.ValoresIncoherentesException
import com.granatum.core.domain.model.AlcanceExportacion
import com.granatum.core.domain.model.CorreccionAplicada
import com.granatum.core.domain.model.FilaRegistro
import com.granatum.core.domain.model.RangoEfectivo
import com.granatum.core.domain.model.TramoPausa
import com.granatum.core.domain.model.ValoresOriginales
import com.granatum.core.domain.service.CalculadoraResumenMensual
import com.granatum.core.domain.service.EscritorCsv
import com.granatum.core.domain.type.EstadoFichaje
import com.granatum.core.domain.type.EstadoSolicitud
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.ValoresFichajeJson
import com.granatum.core.infrastructure.database.entities.EmpleadoEntity
import com.granatum.core.infrastructure.database.entities.FichajeEntity
import com.granatum.core.infrastructure.database.entities.SolicitudCorreccionFichajeEntity
import com.granatum.core.infrastructure.database.mappers.toModel
import com.granatum.core.domain.model.FichajeModel
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import com.granatum.core.infrastructure.database.repositories.FichajeRepository
import com.granatum.core.infrastructure.database.repositories.SolicitudCorreccionFichajeRepository
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.io.OutputStream
import java.lang.reflect.UndeclaredThrowableException
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.text.Collator
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hands the register over as a file (FR-001 to FR-018).
 *
 * ## One read transaction, one snapshot (D-002)
 *
 * The whole file is read inside **one** read-only `REPEATABLE READ`
 * transaction. In Postgres that is snapshot isolation: every query sees the
 * database as it was at the first one. A transaction per batch would let a
 * correction approved mid-export land in the second half of the file and not
 * the first, producing a file that was never the register at any moment.
 *
 * The rows are streamed in batches of `tamano-lote`, walked by key, with four
 * queries per batch and none per row: the ids, the shifts with their breaks,
 * the approved corrections, and the names of whoever requested and approved
 * them. Four rather than the three the plan estimated, because fetching the
 * breaks in the paginated query would make Hibernate paginate in memory (see
 * [FichajeRepository.findIdsSiguienteLote]).
 *
 * ## The audit row goes in afterwards (D-003)
 *
 * [RegistroExportaciones] is called only once the read transaction has closed,
 * complete or not. Writing inside it would take a second connection while the
 * first is held, which is how feature 002 exhausted the pool.
 *
 * ## Exporting writes nothing to the register (FR-018)
 *
 * The transaction is read-only, and the persistence context is cleared after
 * each batch, so nothing read here is ever flushed back.
 */
@Service
class ExportacionService(
    private val empleadoRepository: EmpleadoRepository,
    private val fichajeRepository: FichajeRepository,
    private val solicitudRepository: SolicitudCorreccionFichajeRepository,
    private val json: ValoresFichajeJson,
    private val plazo: PlazoConservacion,
    private val registro: RegistroExportaciones,
    private val entityManager: EntityManager,
    transactionManager: PlatformTransactionManager,
    @param:Value("\${timetracking.exportacion.tamano-lote:500}")
    private val tamanoLote: Int,
    @param:Value("\${timetracking.exportacion.timeout-segundos:120}")
    timeoutSegundos: Int,
    @param:Value("\${timetracking.exportacion.concurrencia:2}")
    concurrencia: Int,
    @param:Value("\${timetracking.exportacion.espera-ms:1000}")
    private val esperaMs: Long,
    private val clock: Clock = Clock.systemUTC()
) {
    /**
     * Exports in flight (D-002). Each one holds a connection for as long as its
     * client takes to read, and the pool is shared with everything else, so
     * the cap is what keeps a few slow downloads from starving the API. Fair,
     * like `VerificadorAcotado` in `auth`, so a waiting export is served in
     * order.
     */
    private val permisos = Semaphore(concurrencia, true)

    private val lectura = TransactionTemplate(transactionManager).apply {
        isReadOnly = true
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        timeout = timeoutSegundos
    }

    /** Validates, authorises and trims, then writes. See [preparar] and [escribir]. */
    fun exportar(
        alcance: AlcanceExportacion,
        desde: LocalDate,
        hasta: LocalDate,
        solicitanteId: UUID,
        rol: Role,
        salida: OutputStream
    ): ResultadoExportacion = escribir(preparar(alcance, desde, hasta, solicitanteId, rol), salida)

    /**
     * One person's calendar month, with the closing block: total, contract type
     * and whether the month has ended (FR-019 to FR-023, D-011).
     */
    fun descargarMensual(
        empleadoId: UUID,
        anio: Int,
        mes: Int,
        solicitanteId: UUID,
        rol: Role,
        salida: OutputStream
    ): ResultadoExportacion = escribir(prepararMensual(empleadoId, anio, mes, solicitanteId, rol), salida)

    /** Same rules as the on-screen summary: month 1 to 12, own month for an EMPLEADO. */
    fun prepararMensual(
        empleadoId: UUID,
        anio: Int,
        mes: Int,
        solicitanteId: UUID,
        rol: Role
    ): ExportacionPreparada {
        if (mes !in 1..12) throw ValoresIncoherentesException("el mes debe estar entre 1 y 12")
        val primero = LocalDate.of(anio, mes, 1)
        return preparar(
            AlcanceExportacion.Mensual(empleadoId, anio, mes),
            primero,
            primero.withDayOfMonth(primero.lengthOfMonth()),
            solicitanteId,
            rol
        )
    }

    /**
     * Everything that can refuse the request, done **before** the first byte.
     *
     * Split from [escribir] for the controller: once a streamed body has
     * started, the status is already `200` and an error can no longer change
     * it. So the range, the ownership and the person are all settled here,
     * while a refusal can still be a proper `4xx`.
     */
    fun preparar(
        alcance: AlcanceExportacion,
        desde: LocalDate,
        hasta: LocalDate,
        solicitanteId: UUID,
        rol: Role
    ): ExportacionPreparada {
        // FR-016
        if (hasta.isBefore(desde)) throw ValoresIncoherentesException("hasta es anterior a desde")

        when (alcance) {
            is AlcanceExportacion.Persona -> comprobarPersona(alcance.empleadoId, solicitanteId, rol)
            is AlcanceExportacion.Mensual -> comprobarPersona(alcance.empleadoId, solicitanteId, rol)
            AlcanceExportacion.Plantilla ->
                if (rol == Role.EMPLEADO) throw ForbiddenException("Solo puede exportar su propio registro")
        }

        val rango = rangoEfectivo(desde, hasta)

        // Last, once nothing else can refuse the request: a refusal after this
        // point would have to remember to give the permit back.
        return ExportacionPreparada(alcance, rango, solicitanteId, rol, reservar())
    }

    /**
     * Taken on the request thread, so saturation is still a `503` with
     * `Retry-After` rather than a `200` that breaks off. Given back by
     * [escribir], however it ends.
     */
    private fun reservar(): Permiso {
        if (!permisos.tryAcquire(esperaMs, TimeUnit.MILLISECONDS)) throw ExportacionSaturadaException()
        return Permiso(permisos)
    }

    /**
     * FR-013: an EMPLEADO is bound to the token subject. The id in the request
     * is compared against it, never trusted.
     */
    private fun comprobarPersona(empleadoId: UUID, solicitanteId: UUID, rol: Role) {
        if (rol == Role.EMPLEADO && empleadoId != solicitanteId) {
            throw ForbiddenException("Solo puede exportar su propio registro")
        }
        if (empleadoRepository.findExistingIds(listOf(empleadoId)).isEmpty()) {
            throw EmpleadoNotFoundException(empleadoId)
        }
    }

    /**
     * FR-017, D-009: the part of the range the retention period still covers.
     *
     * Data older than the boundary may still be in the table - the purge is off
     * by default - but it is outside the period, so it is not handed over as if
     * the period did not exist. A range entirely before the boundary is
     * refused: an empty file would read as "no shifts", which is exactly the
     * misreading FR-017 is there to prevent.
     */
    private fun rangoEfectivo(desde: LocalDate, hasta: LocalDate): RangoEfectivo {
        val corte = plazo.fechaCorte()
        if (hasta.isBefore(corte)) {
            throw ValoresIncoherentesException(
                "el rango es anterior al plazo de conservacion; hay registros desde $corte"
            )
        }
        return if (desde.isBefore(corte)) {
            RangoEfectivo(corte, hasta, recortado = true)
        } else {
            RangoEfectivo(desde, hasta, recortado = false)
        }
    }

    /**
     * Writes the file and records the export, complete or cut short.
     *
     * The fingerprint is SHA-256 over the exact bytes written, BOM included
     * (D-006), so a file can be recognised later by its content alone.
     */
    fun escribir(preparada: ExportacionPreparada, salida: OutputStream): ResultadoExportacion =
        try {
            escribirConPermiso(preparada, salida)
        } finally {
            // The release must survive any failure: a semaphore that loses its
            // permits is a permanent outage, worse than the one it prevents.
            preparada.permiso.devolver()
        }

    private fun escribirConPermiso(preparada: ExportacionPreparada, salida: OutputStream): ResultadoExportacion {
        val generadaEn = clock.instant()
        val digest = MessageDigest.getInstance("SHA-256")
        val out = DigestOutputStream(salida, digest)
        // D-012, FR-012: REPRESENTANTE gets the register without the identity
        // document. The column is OMITTED, not left blank: a "Documento" header
        // over empty cells suggests the data exists and is missing. Location
        // never enters FilaRegistro at all, for any role (FR-007).
        val incluirDocumento = preparada.rol != Role.REPRESENTANTE
        var filas = 0

        try {
            lectura.executeWithoutResult {
                EscritorCsv.escribirCabecera(out, incluirDocumento)
                val mensual = preparada.alcance as? AlcanceExportacion.Mensual
                val delMes = mutableListOf<FichajeModel>()
                val corregidos = mutableSetOf<UUID>()
                var persona: EmpleadoEntity? = null

                recorrer(preparada, incluirDocumento) { quien, fichaje, fila ->
                    EscritorCsv.escribirFila(out, fila, incluirDocumento)
                    filas++
                    if (mensual != null) {
                        persona = quien
                        delMes += fichaje.toModel()
                        if (fila.corregido) corregidos += fichaje.id
                    }
                }

                if (mensual != null) {
                    escribirCierreMensual(out, mensual, persona, delMes, corregidos, incluirDocumento)
                }
            }
            out.flush()
        } catch (e: Throwable) {
            // TransactionTemplate wraps a checked exception - the IOException of
            // a client that hung up - and the caller should see the real one.
            val causa = (e as? UndeclaredThrowableException)?.undeclaredThrowable ?: e
            try {
                anotar(preparada, generadaEn, completada = false, filas = filas, huella = null)
            } catch (fallo: Exception) {
                causa.addSuppressed(fallo)
            }
            throw causa
        }

        val huella = HexFormat.of().formatHex(digest.digest())
        val anotada = anotar(preparada, generadaEn, completada = true, filas = filas, huella = huella)
        return ResultadoExportacion(anotada.id, preparada.rango, filas, huella)
    }

    private fun anotar(
        preparada: ExportacionPreparada,
        generadaEn: Instant,
        completada: Boolean,
        filas: Int,
        huella: String?
    ) = registro.registrar(
        solicitanteId = preparada.solicitanteId,
        rol = preparada.rol,
        alcance = preparada.alcance,
        rango = preparada.rango,
        generadaEn = generadaEn,
        completada = completada,
        filas = filas,
        huella = huella
    )

    /**
     * The closing block of the monthly download, inside the same snapshot as
     * its rows.
     *
     * The total comes from [CalculadoraResumenMensual], the very function behind
     * the on-screen summary, fed the shifts written above it. Reimplementing it
     * is exactly how two totals end up disagreeing (FR-020, D-011).
     */
    private fun escribirCierreMensual(
        out: OutputStream,
        mensual: AlcanceExportacion.Mensual,
        conFichajes: EmpleadoEntity?,
        fichajes: List<FichajeModel>,
        corregidos: Set<UUID>,
        incluirDocumento: Boolean
    ) {
        // A month without shifts still needs the contract type, so the person is
        // read here when the walk did not already bring them.
        val persona = conFichajes ?: empleadoRepository.findAllByIdIn(listOf(mensual.empleadoId)).single()
        val resumen = CalculadoraResumenMensual.calcular(
            empleadoId = mensual.empleadoId,
            anio = mensual.anio,
            mes = mensual.mes,
            tipoContrato = persona.tipoContrato,
            fichajes = fichajes,
            fichajesCorregidos = corregidos
        )
        // FR-022: a month is closed once its last day is over, in Madrid.
        val ultimoDia = LocalDate.of(mensual.anio, mensual.mes, 1).let { it.withDayOfMonth(it.lengthOfMonth()) }
        val cerrado = LocalDate.now(clock.withZone(RangoFechas.ZONA)).isAfter(ultimoDia)

        EscritorCsv.escribirBloqueMensual(
            out, resumen.totalMinutosTrabajados, resumen.tipoContrato, cerrado, incluirDocumento
        )
    }

    /** Must run inside [lectura]: every query here belongs to the same snapshot. */
    private fun recorrer(
        preparada: ExportacionPreparada,
        incluirDocumento: Boolean,
        emitir: (EmpleadoEntity, FichajeEntity, FilaRegistro) -> Unit
    ) {
        val (inicio, fin) = RangoFechas.rango(preparada.rango.desde, preparada.rango.hasta)
        // Names of requesters and approvers, kept across batches: the same few
        // managers approve most corrections, so they are looked up once.
        val nombres = mutableMapOf<UUID, String>()

        personas(preparada).forEach { persona ->
            // Before the first possible key: Postgres keeps microseconds, so one
            // microsecond earlier than the range start precedes every shift in it.
            var entradaPrevia = inicio.minusNanos(1_000)
            var idPrevio = UUID(0, 0)

            while (true) {
                val ids = fichajeRepository.findIdsSiguienteLote(
                    persona.id, inicio, fin, entradaPrevia, idPrevio, PageRequest.of(0, tamanoLote)
                )
                if (ids.isEmpty()) break

                val porId = fichajeRepository.findAllConPausasByIdIn(ids).associateBy { it.id }
                val fichajes = ids.map { porId.getValue(it) }
                val aprobadas = solicitudRepository
                    .findAllByFichajeIdInAndEstadoOrderByResueltaEnAsc(ids, EstadoSolicitud.APROBADA)
                    .groupBy { it.fichaje.id }

                val faltan = aprobadas.values.flatten()
                    .flatMap { listOfNotNull(it.solicitanteId, it.resueltaPorId) }
                    .filterNot(nombres::containsKey)
                    .toSet()
                if (faltan.isNotEmpty()) {
                    empleadoRepository.findAllByIdIn(faltan).forEach { nombres[it.id] = it.nombre }
                }

                fichajes.forEach { f ->
                    emitir(persona, f, fila(persona, f, aprobadas[f.id].orEmpty(), nombres, incluirDocumento))
                }

                val ultimo = fichajes.last()
                entradaPrevia = ultimo.entrada
                idPrevio = ultimo.id
                // A long export would otherwise keep every shift it has read in
                // memory until the transaction ends.
                entityManager.clear()
                if (ids.size < tamanoLote) break
            }
        }
    }

    private fun personas(preparada: ExportacionPreparada): List<EmpleadoEntity> = when (val alcance = preparada.alcance) {
        is AlcanceExportacion.Persona -> empleadoRepository.findAllByIdIn(listOf(alcance.empleadoId))
        is AlcanceExportacion.Mensual -> empleadoRepository.findAllByIdIn(listOf(alcance.empleadoId))
        AlcanceExportacion.Plantilla -> plantilla(preparada.rango)
    }

    /**
     * Everyone with shifts in the range, active or not (FR-015), ordered by
     * name and then by id (D-008).
     *
     * Ordered here and not in SQL: `ORDER BY nombre` depends on the server's
     * collation, which is not the same in local Docker and in Supabase, so the
     * same query could put `Ángel` before or after `Zoe` depending on where it
     * runs - and two exports of the same data would differ (FR-011). A Spanish
     * [Collator] sorts accented letters with their base letter, as a reader
     * expects. The tie is broken by the id's text form: `UUID.compareTo`
     * compares signed halves, an order no reader could predict.
     */
    private fun plantilla(rango: RangoEfectivo): List<EmpleadoEntity> {
        val (inicio, fin) = RangoFechas.rango(rango.desde, rango.hasta)
        val ids = fichajeRepository.findEmpleadoIdsConFichajesEntre(inicio, fin)
        if (ids.isEmpty()) return emptyList()
        val espanol = Collator.getInstance(Locale.of("es", "ES"))
        return empleadoRepository.findAllByIdIn(ids)
            .sortedWith(compareBy(espanol) { e: EmpleadoEntity -> e.nombre }.thenBy { it.id.toString() })
    }

    private fun fila(
        persona: EmpleadoEntity,
        f: FichajeEntity,
        aprobadas: List<SolicitudCorreccionFichajeEntity>,
        nombres: Map<UUID, String>,
        incluirDocumento: Boolean
    ) = FilaRegistro(
        persona = persona.nombre,
        documento = if (incluirDocumento) persona.documentoIdentidad else null,
        puesto = persona.puesto,
        fecha = RangoFechas.fechaCivil(f.entrada),
        entrada = f.entrada,
        salida = f.salida,
        pausas = f.pausas
            .sortedWith(compareBy({ it.inicio }, { it.id.toString() }))
            .map { TramoPausa(it.tipo, it.inicio, it.fin) },
        // FR-006: only a closed shift has worked time. Anything else is empty,
        // never a zero that would claim nothing was worked.
        minutosTrabajados = f.minutosTrabajados.takeIf { f.estado == EstadoFichaje.CERRADO },
        estado = f.estado,
        completadoAPosteriori = f.fueIncompleto,
        corregido = aprobadas.isNotEmpty(),
        original = aprobadas.firstOrNull()?.valoresOriginales?.let(::originales),
        correcciones = aprobadas.map {
            CorreccionAplicada(
                resueltaEn = it.resueltaEn ?: it.createdAt,
                solicitante = nombres[it.solicitanteId] ?: it.solicitanteId.toString(),
                aprobador = it.resueltaPorId?.let { id -> nombres[id] ?: id.toString() }.orEmpty()
            )
        }
    )

    /**
     * The values of the FIRST approved correction are the ones actually clocked
     * (D-010): each later approval snapshots values that were already corrected.
     *
     * An INCOMPLETO shift had no exit, and the snapshot stores its entry in that
     * place to stay well-formed (`CorreccionService.aValores`). Shown as is, it
     * would claim the person left at the moment they arrived, so it is dropped.
     */
    private fun originales(documento: String): ValoresOriginales {
        val v = json.leer(documento)
        return ValoresOriginales(
            entrada = v.entrada,
            salida = v.salida.takeIf { it != v.entrada },
            pausas = v.pausas.map { TramoPausa(it.tipo, it.inicio, it.fin) }
        )
    }
}

/**
 * A request already validated and authorised, ready to write, holding one of
 * the export permits until [ExportacionService.escribir] gives it back.
 */
class ExportacionPreparada(
    val alcance: AlcanceExportacion,
    val rango: RangoEfectivo,
    val solicitanteId: UUID,
    val rol: Role,
    internal val permiso: Permiso
)

/** One export permit, returned at most once whatever path ends the export. */
class Permiso(private val semaforo: Semaphore) {
    private val devuelto = AtomicBoolean(false)

    fun devolver() {
        if (devuelto.compareAndSet(false, true)) semaforo.release()
    }
}

data class ResultadoExportacion(
    val exportacionId: UUID,
    val rango: RangoEfectivo,
    val filas: Int,
    val huella: String
)
