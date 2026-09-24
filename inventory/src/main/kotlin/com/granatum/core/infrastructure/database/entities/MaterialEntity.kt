package com.granatum.core.infrastructure.database.entities

import com.granatum.core.domain.type.EstadoMaterial
import jakarta.persistence.Column
import jakarta.persistence.CollectionTable
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "materiales")
@EntityListeners(AuditingEntityListener::class)
class MaterialEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    var nombre: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "categoria_id", nullable = false)
    var categoria: CategoriaEntity,

    @Column(nullable = false)
    var cantidadDisponible: Int,

    @Column(nullable = false)
    var cantidadTotal: Int,

    @Embedded
    var tamano: TamanoEmbeddable,

    @Column(nullable = false)
    var color: String,

    @Column(nullable = false)
    var materialFisico: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var estado: EstadoMaterial,

    @Column(nullable = false)
    var ubicacion: String,

    @Column(nullable = false, precision = 10, scale = 2)
    var precioUnitario: BigDecimal,

    @Column(nullable = false)
    var proveedor: String,

    @ElementCollection
    @CollectionTable(name = "material_fotos", joinColumns = [JoinColumn(name = "material_id")])
    @Column(name = "foto_url", nullable = false)
    var fotos: MutableList<String> = mutableListOf(),

    @CreatedDate
    @Column(nullable = false, updatable = false)
    var fechaAlta: Instant = Instant.now(),

    @LastModifiedDate
    @Column(nullable = false)
    var fechaUltimaModificacion: Instant = Instant.now()
)
