package com.granatum.core

import com.granatum.core.domain.exception.AusenciaNoModificableException
import com.granatum.core.domain.exception.DatosAusenciaInvalidosException
import com.granatum.core.domain.exception.RangoAusenciaInvalidoException
import com.granatum.core.domain.exception.ResolucionPropiaAusenciaException
import com.granatum.core.domain.model.CausaPermiso
import com.granatum.core.domain.model.EstadoAusencia
import com.granatum.core.domain.model.TipoAusencia
import com.granatum.core.service.AusenciaService
import com.granatum.core.service.NuevaAusencia
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Sick leave and paid leave (feature 007, US3): FR-002, FR-006 to FR-008, FR-017, SC-005. */
@Testcontainers
@SpringBootTest(classes = [AbsencesTestApplication::class])
class BajasYPermisosIT : BaseAusenciasIT() {

    @Autowired lateinit var servicio: AusenciaService
    @Autowired lateinit var jdbc: JdbcTemplate

    private val hoy get() = LocalDate.now(ZoneId.of("Europe/Madrid"))
    private val encargado = UUID.randomUUID()

    private fun baja(desde: LocalDate, hasta: LocalDate? = null, comentario: String? = null) =
        NuevaAusencia(TipoAusencia.BAJA_MEDICA, null, desde, hasta, comentario)

    @Test
    fun `an ENCARGADO registers a sick leave - approved, open, and no holidays used`() {
        val persona = directorio.activa()

        val registrada = servicio.registrar(encargado, persona, baja(hoy))

        assertEquals(EstadoAusencia.APROBADA, registrada.estado)
        assertEquals(encargado, registrada.resueltaPor)
        assertNull(registrada.hasta)
        assertEquals(30, servicio.saldo(persona, hoy.year).disponibles)
    }

    /** FR-007, SC-005: a comment on a sick leave would be health data. */
    @Test
    fun `a sick leave with a comment is refused, and the database refuses it too`() {
        val persona = directorio.activa()

        assertFailsWith<DatosAusenciaInvalidosException> {
            servicio.registrar(encargado, persona, baja(hoy, comentario = "Gripe"))
        }
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                """
                INSERT INTO ausencias (id, empleado_id, tipo, desde, estado, comentario, solicitada_por, solicitada_en,
                                       resuelta_por, resuelta_en)
                VALUES (gen_random_uuid(), ?, 'BAJA_MEDICA', current_date, 'APROBADA', 'Gripe', ?, now(), ?, now())
                """.trimIndent(),
                persona, encargado, encargado
            )
        }
    }

    @Test
    fun `nobody registers or requests their own sick leave`() {
        val persona = directorio.activa()

        assertFailsWith<ResolucionPropiaAusenciaException> { servicio.registrar(persona, persona, baja(hoy)) }
        assertFailsWith<ResolucionPropiaAusenciaException> { servicio.solicitar(persona, baja(hoy, hoy.plusDays(2))) }
    }

    @Test
    fun `the discharge closes an open sick leave, once`() {
        val persona = directorio.activa()
        val abierta = servicio.registrar(encargado, persona, baja(hoy.minusDays(5)))

        val cerrada = servicio.darAlta(abierta.id, encargado, hoy)

        assertEquals(hoy, cerrada.hasta)
        assertFailsWith<AusenciaNoModificableException> { servicio.darAlta(abierta.id, encargado, hoy.plusDays(1)) }
    }

    @Test
    fun `a discharge before the start of the leave is refused`() {
        val persona = directorio.activa()
        val abierta = servicio.registrar(encargado, persona, baja(hoy))

        assertFailsWith<RangoAusenciaInvalidoException> { servicio.darAlta(abierta.id, encargado, hoy.minusDays(1)) }
    }

    @Test
    fun `paid leave needs its cause, is pending and uses no holidays`() {
        val persona = directorio.activa()

        val permiso = servicio.solicitar(
            persona, NuevaAusencia(TipoAusencia.PERMISO, CausaPermiso.FALLECIMIENTO_FAMILIAR, hoy, hoy.plusDays(1), null)
        )

        assertEquals(EstadoAusencia.PENDIENTE, permiso.estado)
        assertEquals(CausaPermiso.FALLECIMIENTO_FAMILIAR, permiso.causa)
        assertEquals(30, servicio.saldo(persona, hoy.year).disponibles)
    }

    @Test
    fun `a cause belongs to paid leave and only to it`() {
        val persona = directorio.activa()

        assertFailsWith<DatosAusenciaInvalidosException> {
            servicio.solicitar(persona, NuevaAusencia(TipoAusencia.PERMISO, null, hoy, hoy, null))
        }
        assertFailsWith<DatosAusenciaInvalidosException> {
            servicio.solicitar(persona, NuevaAusencia(TipoAusencia.VACACIONES, CausaPermiso.OTRO, hoy.plusDays(1), hoy.plusDays(1), null))
        }
    }

    @Test
    fun `only a sick leave may be registered without an end`() {
        assertFailsWith<RangoAusenciaInvalidoException> {
            servicio.registrar(encargado, directorio.activa(), NuevaAusencia(TipoAusencia.VACACIONES, null, hoy, null, null))
        }
    }

    /** FR-011: regularising past holidays on someone's behalf. */
    @Test
    fun `an ENCARGADO may register past holidays, approved`() {
        val persona = directorio.activa()

        val pasadas = servicio.registrar(
            encargado, persona, NuevaAusencia(TipoAusencia.VACACIONES, null, hoy.minusDays(10), hoy.minusDays(8), null)
        )

        assertEquals(EstadoAusencia.APROBADA, pasadas.estado)
    }
}
