package com.granatum.core.infrastructure.database.entities

import com.granatum.core.domain.type.Role
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.UUID

/**
 * The credentials of one person.
 *
 * [empleadoId] is the id of the person whose working time is recorded, and it is
 * also the JWT subject (decided in feature 001). It is deliberately not this
 * row's own [id]: keeping them apart is what lets the token identify the person
 * rather than the credential, so a reset that replaces the credential does not
 * change who the fichajes belong to.
 *
 * There is no `activo` column. Whether someone is employed is a fact about the
 * person, owned by `timetracking`; duplicating it here would be two sources of
 * truth for one fact, and their divergence would mean somebody dismissed could
 * still sign in.
 *
 * Plain `class`, never a `data class`: a data class generates equals/hashCode
 * over every field, so the hash changes when the entity is mutated and its
 * membership of any Set is corrupted, and copy() produces detached duplicates of
 * a managed entity.
 */
@Entity
@Table(name = "cuentas_acceso")
@EntityListeners(AuditingEntityListener::class)
class CuentaAccesoEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "empleado_id", nullable = false, unique = true)
    val empleadoId: UUID,

    /**
     * Already normalised when it gets here: trimmed and lowercased with
     * `Locale.ROOT` by `NormalizadorEmail`, in one place, because normalising
     * at one call site and forgetting at another is exactly how one person ends
     * up with two accounts (FR-028).
     *
     * Personal data: never logged, and absent from [toString].
     */
    @Column(nullable = false, length = 254, unique = true)
    var email: String,

    /**
     * What this credential may do (FR-005). Stored here rather than on the
     * person because it is a property of the credential: `puesto` says what
     * someone does, `rol` says what they may read and write.
     *
     * `EnumType.STRING`, never ORDINAL: an ordinal would silently remap every
     * existing row the day a value is inserted into the middle of [Role], and
     * the four roles are the product's authorisation map (principle IV).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var rol: Role,

    /**
     * Output of `DelegatingPasswordEncoder`, prefix included
     * (`{argon2}$argon2id$v=19$m=65536,t=3,p=1$...`). The prefix is what lets
     * the parameters - or the algorithm - change later without a schema
     * migration: each stored hash carries its own.
     */
    @Column(name = "password_hash", nullable = false, length = 255)
    var passwordHash: String,

    /**
     * Defaults to `true` because every account is born from a temporary
     * password: there is no sign-up route that skips the change (FR-019).
     */
    @Column(name = "requiere_cambio_password", nullable = false)
    var requiereCambioPassword: Boolean = true,

    @Embedded
    var estadoBloqueo: EstadoBloqueoEmbeddable = EstadoBloqueoEmbeddable()
) {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CuentaAccesoEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()

    /**
     * Neither [email] nor [passwordHash] appear here. `toString` is the quiet
     * route into a log: a debugger, a log statement and a stack trace all reach
     * for it.
     *
     * Note that this is necessary but not sufficient. Hibernate's
     * `EntityPrinter` dumps every persistent property by reflection and
     * bypasses this method entirely, which is why that logger is pinned to WARN
     * in both the application and the test configuration - a leak found exactly
     * this way in feature 001.
     */
    override fun toString(): String =
        "CuentaAccesoEntity(id=$id, empleadoId=$empleadoId, rol=$rol, " +
            "requiereCambioPassword=$requiereCambioPassword)"
}
