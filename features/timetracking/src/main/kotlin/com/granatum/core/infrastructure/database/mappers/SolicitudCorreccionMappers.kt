package com.granatum.core.infrastructure.database.mappers

import com.granatum.core.domain.model.SolicitudCorreccionModel
import com.granatum.core.infrastructure.database.ValoresFichajeJson
import com.granatum.core.infrastructure.database.entities.SolicitudCorreccionFichajeEntity

/**
 * Entity -> Model, deserialising the two JSONB documents through
 * [ValoresFichajeJson] - the module's own mapper, deliberately not the web
 * layer's. See that class for why.
 */
fun SolicitudCorreccionFichajeEntity.toModel(json: ValoresFichajeJson): SolicitudCorreccionModel =
    SolicitudCorreccionModel(
        id = id,
        fichajeId = fichaje.id,
        solicitanteId = solicitanteId,
        motivo = motivo,
        estado = estado,
        valoresPropuestos = json.leer(valoresPropuestos),
        valoresOriginales = valoresOriginales?.let { json.leer(it) },
        resueltaPorId = resueltaPorId,
        resueltaEn = resueltaEn,
        motivoResolucion = motivoResolucion,
        creadaEn = createdAt
    )
