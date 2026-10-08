package com.granatum.core

import com.granatum.core.domain.service.CodigoVerificacion
import com.granatum.core.domain.type.EstadoSolicitudRegistro
import com.granatum.core.infrastructure.database.entities.SolicitudRegistroEntity
import com.granatum.core.infrastructure.database.repositories.EventoSeguridadRepository
import com.granatum.core.infrastructure.database.repositories.SolicitudRegistroRepository
import com.granatum.core.service.AprobacionRegistroService
import com.granatum.core.service.DatosRegistro
import com.granatum.core.service.RegistroService
import com.granatum.core.service.ResultadoRegistro
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertIs

/**
 * Shared helpers for the sign-up tests (feature 005). Other tests' pending
 * requests are expired by [BaseAuthIT] before each test.
 */
abstract class BaseRegistroIT : BaseAuthIT() {

    @Autowired lateinit var registro: RegistroService
    @Autowired lateinit var aprobacion: AprobacionRegistroService
    @Autowired lateinit var solicitudes: SolicitudRegistroRepository
    @Autowired lateinit var eventos: EventoSeguridadRepository
    @Autowired lateinit var jdbc: JdbcTemplate

    protected val password = "Granatum-Florista-2026!"
    protected val adminId: UUID = UUID.randomUUID()

    protected fun dniValido(): String {
        val numero = (10_000_000..99_999_999).random()
        return "$numero${"TRWAGMYFPDXBNJZSQVHLCKE"[numero % 23]}"
    }

    protected fun datos(email: String = correoUnico(), documento: String = dniValido(), nombre: String = "Ana Florista") =
        DatosRegistro(email, password, nombre, documento)

    /** Signs up and returns the pending request together with its clear code. */
    protected fun pendiente(
        email: String = correoUnico(),
        documento: String = dniValido()
    ): Pair<SolicitudRegistroEntity, String> {
        val codigo = assertIs<ResultadoRegistro.Pendiente>(registro.registrar(datos(email, documento))).codigoVerificacion
        val solicitud = solicitudes.findAllByEstadoOrderByCreadaEnAsc(EstadoSolicitudRegistro.PENDIENTE)
            .single { it.email == email && CodigoVerificacion.coincide(it.id, codigo, it.codigoHash!!) }
        return solicitud to codigo
    }

    protected fun codigoDistintoDe(codigo: String): String {
        while (true) {
            val otro = CodigoVerificacion.generar()
            if (otro != codigo) return otro
        }
    }
}
