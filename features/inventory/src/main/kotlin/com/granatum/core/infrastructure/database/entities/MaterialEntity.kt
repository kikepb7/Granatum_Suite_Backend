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
@Table(name = "materials")
@EntityListeners(AuditingEntityListener::class)
class MaterialEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    var category: CategoryEntity,

    @Column(nullable = false, length = 500)
    var description: String,

    @Embedded
    var dimensions: MaterialDimensionsEmbeddable,

    @Column(nullable = false)
    var quantity: Int,

    @Embedded
    var location: MaterialLocationEmbeddable,

    /** Path of the object in the private bucket (research.md D-009). */
    @Column(length = 300)
    var imagePath: String? = null,

    @CreatedDate
    @Column(nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @LastModifiedDate
    @Column(nullable = false)
    var updatedAt: Instant = Instant.now()
)
