package com.granatum.core

import com.granatum.core.service.DeteccionHuerfanasService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FR-029c: accounts whose person no longer exists are detected.
 *
 * Uses the module's `DirectorioEmpleados` double, which is what lets a test
 * make a person disappear after their account exists - precisely the situation
 * the database cannot prevent, because there is no foreign key across modules.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class CuentasHuerfanasIT : BaseAuthIT() {

    @Autowired lateinit var deteccion: DeteccionHuerfanasService

    @Test
    fun `an account whose person disappeared is listed and a normal one is not`() {
        val huerfana = cuentaActiva()
        val normal = cuentaActiva()
        directorio.olvidar(huerfana.empleadoId)

        val ids = deteccion.buscar().map { it.cuentaId }

        assertTrue(huerfana.id in ids, "the orphan must surface")
        assertFalse(normal.id in ids, "a healthy account must not")
    }

    /**
     * An inactive person still *exists*. Confusing the two would list everyone
     * on leave as an orphan, and the obvious "fix" - deleting their
     * credentials - would cut them off from a working-time record the law says
     * they are entitled to see.
     */
    @Test
    fun `an account whose person is merely inactive is not an orphan`() {
        val cuenta = cuentaActiva()
        directorio.registrarInactivo(cuenta.empleadoId)

        assertFalse(cuenta.id in deteccion.buscar().map { it.cuentaId })
    }

    /** The listing carries ids, and nothing that identifies the person further. */
    @Test
    fun `the listing does not carry the email`() {
        val huerfana = cuentaActiva()
        directorio.olvidar(huerfana.empleadoId)

        val fila = deteccion.buscar().single { it.cuentaId == huerfana.id }

        assertFalse(fila.toString().contains(huerfana.email))
    }

    /**
     * More accounts than one batch, so the paging is exercised and an orphan on
     * the second page is not silently skipped.
     */
    @Test
    fun `orphans beyond the first batch are found too`() {
        val muchas = (1..DeteccionHuerfanasService.TAMANO_LOTE + 5).map { cuentaActiva() }
        val ultima = muchas.maxBy { it.id }
        directorio.olvidar(ultima.empleadoId)

        assertTrue(
            ultima.id in deteccion.buscar().map { it.cuentaId },
            "the account with the highest id lands on the last page: if it is missing, " +
                "the sweep stops after the first batch"
        )
    }
}
