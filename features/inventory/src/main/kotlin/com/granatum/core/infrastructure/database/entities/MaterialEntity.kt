package com.granatum.core.infrastructure.database.entities

import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "materiales")
@EntityListeners(AuditingEntityListener::class)
class MaterialEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "categoria_id", nullable = false)
    var categoria: CategoriaEntity,

    @Column(nullable = false, length = 500)
    var descripcion: String,

    @Embedded
    var dimensiones: DimensionesEmbeddable,

    @Column(nullable = false)
    var cantidad: Int,

    @Embedded
    var localizacion: LocalizacionEmbeddable,

    /** Path of the object in the private bucket (research.md D-009). */
    @Column(length = 300)
    var imagenRuta: String? = null,

    @CreatedDate
    @Column(nullable = false, updatable = false)
    var fechaAlta: Instant = Instant.now(),

    @LastModifiedDate
    @Column(nullable = false)
    var fechaUltimaModificacion: Instant = Instant.now()
)
