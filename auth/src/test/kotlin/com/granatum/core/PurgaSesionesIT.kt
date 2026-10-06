package com.granatum.core

import com.granatum.core.infrastructure.database.entities.CuentaAccesoEntity
import com.granatum.core.infrastructure.database.entities.SesionRenovacionEntity
import com.granatum.core.infrastructure.database.repositories.SesionRenovacionRepository
import com.granatum.core.scheduling.PurgaSesionesJob
import com.granatum.core.service.GeneradorTokenRenovacion
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The session purge (D-015, an addition of the plan).
 *
 * The boundary that matters is the one between dead and alive: a purge that
 * took a live session would log somebody out at 04:45 for no reason, which in
 * this product means a person who cannot clock in when they arrive.
 *
 * Rows are inserted with the dates the test needs, because what is under test
 * is the bound in the query, not the passage of time.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class PurgaSesionesIT : BaseAuthIT() {

    @Autowired lateinit var job: PurgaSesionesJob
    @Autowired lateinit var sesiones: SesionRenovacionRepository
    @Autowired lateinit var generadorToken: GeneradorTokenRenovacion

    private val ahora: Instant = Instant.now()

    private fun sesion(
        cuenta: CuentaAccesoEntity,
        expiraEn: Instant = ahora.plus(30, ChronoUnit.DAYS),
        usadaEn: Instant? = null,
        revocadaEn: Instant? = null
    ): String {
        val hash = generadorToken.hash(generadorToken.generar())
        sesiones.save(
            SesionRenovacionEntity(
                cuenta = cuenta,
                tokenHash = hash,
                expiraEn = expiraEn,
                creadaEn = ahora.minus(400, ChronoUnit.DAYS),
                usadaEn = usadaEn,
                revocadaEn = revocadaEn,
                motivoRevocacion = if (revocadaEn != null) "LOGOUT" else null
            )
        )
        return hash
    }

    private fun hace(dias: Long) = ahora.minus(dias, ChronoUnit.DAYS)

    @Test
    fun `a session used 31 days ago is purged`() {
        val hash = sesion(cuentaActiva(), usadaEn = hace(31))
        job.purgar()
        assertNull(sesiones.findByTokenHash(hash))
    }

    @Test
    fun `a session revoked 31 days ago is purged`() {
        val hash = sesion(cuentaActiva(), revocadaEn = hace(31))
        job.purgar()
        assertNull(sesiones.findByTokenHash(hash))
    }

    @Test
    fun `a session expired 31 days ago is purged`() {
        val hash = sesion(cuentaActiva(), expiraEn = hace(31))
        job.purgar()
        assertNull(sesiones.findByTokenHash(hash))
    }

    /** Inside the 30 days the row stays: it may still be wanted in an investigation. */
    @Test
    fun `a session dead for only 29 days survives`() {
        val usada = sesion(cuentaActiva(), usadaEn = hace(29))
        val revocada = sesion(cuentaActiva(), revocadaEn = hace(29))
        val caducada = sesion(cuentaActiva(), expiraEn = hace(29))

        job.purgar()

        assertNotNull(sesiones.findByTokenHash(usada))
        assertNotNull(sesiones.findByTokenHash(revocada))
        assertNotNull(sesiones.findByTokenHash(caducada))
    }

    /**
     * The one that matters most. Created 400 days ago, still unused, unrevoked
     * and unexpired: it is a session somebody is using, and age alone must
     * never make it purgeable.
     */
    @Test
    fun `a live session is never purged however old it is`() {
        val viva = sesion(cuentaActiva(), expiraEn = ahora.plus(1, ChronoUnit.DAYS))

        job.purgar()

        assertNotNull(
            sesiones.findByTokenHash(viva),
            "purging a live session would sign somebody out overnight - here, someone " +
                "unable to clock in when they arrive"
        )
    }
}
