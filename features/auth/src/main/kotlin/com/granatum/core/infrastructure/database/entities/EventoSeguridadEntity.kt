package com.granatum.core.infrastructure.database.entities

import com.granatum.core.domain.type.TipoEventoSeguridad
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * APPEND-ONLY. One access attempt or credential change (FR-017).
 *
 * Every field is `val`: there is no transition that could change a recorded
 * fact, and no `updatedAt` column, deliberately. The repository extends
 * `Repository<T, ID>` and declares only `save` and reads, because not calling
 * `delete` is not enough - an interface that offers it will eventually be used
 * by accident (principle III, last rule).
 *
 * [cuentaId] is a bare UUID and not a `@ManyToOne`: an association here would be
 * a path for a delete to cascade into the immutable table, the same reason
 * `fichaje_eventos` has no foreign keys in feature 001.
 *
 * What is deliberately absent, and why:
 *
 *  - **The email address**, including for an attempt with an unknown one, where
 *    [cuentaId] is simply `null`. Storing it would mean keeping a personal datum
 *    about someone who is not a user, over an attempt that may not have been
 *    theirs. The cost is that email enumeration cannot be detected from here;
 *    that belongs to the hardening feature.
 *  - **The source IP address.** Personal data, and per-origin limiting is out of
 *    scope by decision of the spec. Collecting it "just in case" is collecting
 *    without a purpose.
 *  - **Any password or token**, in any form, not even hashed (SC-009).
 */
@Entity
@Table(name = "eventos_seguridad")
class EventoSeguridadEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    /** `null` when the email matched no account - see the class comment. */
    @Column(name = "cuenta_id")
    val cuentaId: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    val tipo: TipoEventoSeguridad,

    @Column(name = "ocurrido_en", nullable = false)
    val ocurridoEn: Instant
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EventoSeguridadEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()

    /** Safe in full: by construction this row holds no personal data. */
    override fun toString(): String =
        "EventoSeguridadEntity(id=$id, tipo=$tipo, ocurridoEn=$ocurridoEn)"
}
