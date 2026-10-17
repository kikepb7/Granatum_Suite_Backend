package com.granatum.core.infrastructure.contract

import com.granatum.core.domain.contract.UsoMateriales
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.EstadoEvento
import com.granatum.core.infrastructure.database.repositories.EventoMaterialRepository
import org.springframework.stereotype.Component

/** The events side of [UsoMateriales] (feature 011): out at a scheduled event means in use. */
@Component
class UsoMaterialesEventos(private val lineas: EventoMaterialRepository) : UsoMateriales {
    override fun enUso(materialId: EntityId): Boolean = lineas.usadoEnEstado(materialId, EstadoEvento.PROGRAMADO)
}
