package com.granatum.core.infrastructure.database.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * One open session on one device. An account may have several (FR-012).
 *
 * Only the SHA-256 of the token is stored, never its value (FR-011). A fast hash
 * is the right choice here and would be wrong for a password: the ban on
 * MD5/SHA in principle VI exists because passwords have little entropy and must
 * be expensive to guess, whereas this is 256 random bits with nothing to guess,
 * and a slow hash would only make every legitimate renewal more expensive.
 *
 * [usadaEn] and [revocadaEn] are separate rather than one state column because
 * they mean different things - "rotated normally" versus "cut short by a logout
 * or a reset" - and the second is the one that matters in an investigation.
 */
@Entity
@Table(name = "sesiones_renovacion")
class SesionRenovacionEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    /**
     * LAZY on purpose: nothing that reads a session needs the account loaded,
     * and the `allOpen` plugin in `granatum.kotlin-common` is what makes this
     * entity proxyable so that LAZY actually is lazy. Without it the Kotlin
     * class would compile `final`, Hibernate could not proxy it, and the
     * association would silently become eager.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cuenta_id", nullable = false)
    val cuenta: CuentaAccesoEntity,

    /** SHA-256 in hexadecimal: always exactly 64 characters. */
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    val tokenHash: String,

    @Column(name = "expira_en", nullable = false)
    val expiraEn: Instant,

    @Column(name = "creada_en", nullable = false)
    val creadaEn: Instant,

    /** Not null = already rotated. This is the single-use mark (FR-008). */
    @Column(name = "usada_en")
    var usadaEn: Instant? = null,

    /** Not null = invalidated without being used. */
    @Column(name = "revocada_en")
    var revocadaEn: Instant? = null,

    @Column(name = "motivo_revocacion", length = 20)
    var motivoRevocacion: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SesionRenovacionEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()

    /**
     * [tokenHash] is absent. It is not the token, but it is the lookup key for a
     * live session, and a log line carrying it would let anyone reading the logs
     * find and revoke someone's session. [cuenta] is absent too, for a second
     * reason: touching a lazy association from `toString` is how a
     * `LazyInitializationException` appears in the middle of an error path.
     */
    override fun toString(): String =
        "SesionRenovacionEntity(id=$id, expiraEn=$expiraEn, " +
            "usada=${usadaEn != null}, revocada=${revocadaEn != null})"
}
