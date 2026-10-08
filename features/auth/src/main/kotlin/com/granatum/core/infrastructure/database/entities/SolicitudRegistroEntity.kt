package com.granatum.core.infrastructure.database.entities

import com.granatum.core.domain.type.EstadoSolicitudRegistro
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A sign-up request (feature 005, V20).
 *
 * Personal fields are nullable because they only exist while the request is
 * pending: [resolver] is the single place that moves it to a final state, and it
 * empties them in the same step, so no code path can forget one - and if one
 * did, the CHECK in V20 would refuse the row.
 */
@Entity
@Table(name = "solicitudes_registro")
class SolicitudRegistroEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(length = 254)
    var email: String?,

    @Column(length = 150)
    var nombre: String?,

    @Column(name = "documento_identidad", length = 20)
    var documentoIdentidad: String?,

    @Column(name = "password_hash", length = 255)
    var passwordHash: String?,

    @Column(name = "codigo_hash", length = 64)
    var codigoHash: String?,

    @Column(name = "creada_en", nullable = false, updatable = false)
    val creadaEn: Instant
) {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    var estado: EstadoSolicitudRegistro = EstadoSolicitudRegistro.PENDIENTE
        protected set

    @Column(name = "intentos_codigo", nullable = false)
    var intentosCodigo: Short = 0
        protected set

    @Column(name = "resuelta_en")
    var resueltaEn: Instant? = null
        protected set

    @Column(name = "resuelta_por")
    var resueltaPor: UUID? = null
        protected set

    @Column(name = "cuenta_id")
    var cuentaId: UUID? = null
        protected set

    val pendiente: Boolean get() = estado == EstadoSolicitudRegistro.PENDIENTE

    /**
     * Counts a wrong verification code and cancels the request on the fifth
     * (FR-018). Returns whether this attempt cancelled it.
     */
    fun registrarCodigoIncorrecto(ahora: Instant): Boolean {
        check(pendiente) { "Solo una solicitud pendiente acumula intentos" }
        intentosCodigo = (intentosCodigo + 1).toShort()
        if (intentosCodigo >= MAX_INTENTOS_CODIGO) {
            resolver(EstadoSolicitudRegistro.ANULADA, ahora, por = null)
            return true
        }
        return false
    }

    /**
     * Moves the request to a final state and empties every personal field in
     * the same step (FR-026).
     */
    fun resolver(estadoFinal: EstadoSolicitudRegistro, ahora: Instant, por: UUID?, cuenta: UUID? = null) {
        check(pendiente) { "La solicitud ya estaba resuelta" }
        require(estadoFinal != EstadoSolicitudRegistro.PENDIENTE) { "PENDIENTE no es un estado final" }
        require((estadoFinal == EstadoSolicitudRegistro.APROBADA) == (cuenta != null)) {
            "Solo una solicitud aprobada lleva cuenta, y siempre la lleva"
        }
        estado = estadoFinal
        resueltaEn = ahora
        resueltaPor = por
        cuentaId = cuenta
        email = null
        nombre = null
        documentoIdentidad = null
        passwordHash = null
        codigoHash = null
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SolicitudRegistroEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()

    // No email, name, document or hashes: personal data (principle VI).
    override fun toString(): String = "SolicitudRegistroEntity(id=$id, estado=$estado)"

    companion object {
        const val MAX_INTENTOS_CODIGO = 5
    }
}
