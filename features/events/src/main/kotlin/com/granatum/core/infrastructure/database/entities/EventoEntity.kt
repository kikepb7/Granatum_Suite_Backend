package com.granatum.core.infrastructure.database.entities

import com.granatum.core.domain.type.EstadoEvento
import jakarta.persistence.Column
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
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "eventos")
@EntityListeners(AuditingEntityListener::class)
class EventoEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false, length = 100)
    var nombre: String,

    @Column(length = 500)
    var descripcion: String?,

    @Column(nullable = false)
    var fecha: LocalDate,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var estado: EstadoEvento = EstadoEvento.PROGRAMADO,

    @Column(nullable = false, updatable = false)
    val creadoPor: UUID,

    @Column
    var finalizadoEn: Instant? = null,

    @CreatedDate
    @Column(nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @LastModifiedDate
    @Column(nullable = false)
    var updatedAt: Instant = Instant.now()
)
