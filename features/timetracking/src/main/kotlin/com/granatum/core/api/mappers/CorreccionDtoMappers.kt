package com.granatum.core.api.mappers

import com.granatum.core.api.dto.CorreccionDto
import com.granatum.core.api.dto.ValoresFichajeDto
import com.granatum.core.api.dto.ValoresPausaDto
import com.granatum.core.domain.model.SolicitudCorreccionModel
import com.granatum.core.domain.model.ValoresFichaje
import com.granatum.core.domain.model.ValoresPausa

fun SolicitudCorreccionModel.toDto(): CorreccionDto = CorreccionDto(
    id = id,
    fichajeId = fichajeId,
    solicitanteId = solicitanteId,
    motivo = motivo,
    estado = estado,
    valoresPropuestos = valoresPropuestos.toDto(),
    valoresOriginales = valoresOriginales?.toDto(),
    resueltaPorId = resueltaPorId,
    resueltaEn = resueltaEn,
    motivoResolucion = motivoResolucion,
    creadaEn = creadaEn
)

fun ValoresFichaje.toDto(): ValoresFichajeDto = ValoresFichajeDto(
    entrada = entrada,
    salida = salida,
    pausas = pausas.map { it.toDto() }
)

fun ValoresPausa.toDto(): ValoresPausaDto = ValoresPausaDto(
    tipo = tipo,
    inicio = inicio,
    fin = fin
)

fun ValoresFichajeDto.toModel(): ValoresFichaje = ValoresFichaje(
    entrada = entrada,
    salida = salida,
    pausas = pausas.map { ValoresPausa(it.tipo, it.inicio, it.fin) }
)
