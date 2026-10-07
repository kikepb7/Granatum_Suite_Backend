package com.granatum.core

import com.granatum.core.service.EscritorCredenciales
import com.granatum.core.service.GestorBloqueoCuenta
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals

/**
 * The lost update that `EscritorCredenciales` exists to prevent.
 *
 * Changing or resetting a password hashes first - ~110 ms, outside any
 * transaction - and writes afterwards. The first version wrote by calling
 * `save` on the entity loaded **before** the hash, which by then is detached:
 * `save` merges every column from that stale copy, so a failed sign-in that
 * bumped the lockout counter during the hash was silently reverted.
 *
 * Reproduced deterministically rather than with threads: load the account,
 * record a failure the way a concurrent sign-in would, then write the new
 * credential. The old code reverts the counter to its loaded value; the
 * current one re-reads the row under lock and leaves it alone.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class EscritorCredencialesIT : BaseAuthIT() {

    @Autowired lateinit var escritor: EscritorCredenciales
    @Autowired lateinit var gestorBloqueo: GestorBloqueoCuenta

    @Test
    fun `writing a new credential does not revert a failure counted in the meantime`() {
        val cuenta = cuentaActiva()
        val cargadaAntes = cuentas.findById(cuenta.id).orElseThrow()
        assertEquals(0, cargadaAntes.estadoBloqueo.intentosFallidos.toInt())

        // What a concurrent failed sign-in does while the change is hashing.
        gestorBloqueo.registrarFallo(cuenta.id)
        gestorBloqueo.registrarFallo(cuenta.id)

        escritor.aplicar(
            cuentaId = cargadaAntes.id,
            hash = verificador.codificar("Granatum-2027!"),
            requiereCambio = false,
            levantarBloqueo = false
        )

        assertEquals(
            2,
            cuentas.findById(cuenta.id).orElseThrow().estadoBloqueo.intentosFallidos.toInt(),
            "a password change must not wipe failures counted while it was hashing - " +
                "that would hand an attacker free guesses by timing them around a change"
        )
    }

    @Test
    fun `a reset does clear the counter, because that is what it is for`() {
        val cuenta = cuentaActiva()
        gestorBloqueo.registrarFallo(cuenta.id)
        gestorBloqueo.registrarFallo(cuenta.id)

        escritor.aplicar(
            cuentaId = cuenta.id,
            hash = verificador.codificar("Granatum-2027!"),
            requiereCambio = true,
            levantarBloqueo = true
        )

        assertEquals(0, cuentas.findById(cuenta.id).orElseThrow().estadoBloqueo.intentosFallidos.toInt())
    }
}
