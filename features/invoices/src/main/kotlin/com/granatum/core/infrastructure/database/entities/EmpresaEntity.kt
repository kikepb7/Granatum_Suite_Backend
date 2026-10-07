package com.granatum.core.infrastructure.database.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** The company's own data: one row, `id = 1` (FR-029). */
@Entity
@Table(name = "empresa")
class EmpresaEntity(
    @Id
    val id: Short = 1,

    @Column(name = "razon_social", nullable = false, length = 200)
    var razonSocial: String,

    @Column(name = "nif", nullable = false, length = 20)
    var nif: String,

    @Column(name = "nif_normalizado", nullable = false, length = 20)
    var nifNormalizado: String,

    @Column(name = "actualizada_por", nullable = false)
    var actualizadaPor: UUID,

    @Column(name = "actualizada_en", nullable = false)
    var actualizadaEn: Instant
) {
    override fun equals(other: Any?): Boolean = this === other || (other is EmpresaEntity && id == other.id)
    override fun hashCode(): Int = javaClass.hashCode()
    override fun toString(): String = "EmpresaEntity(id=$id)"
}
