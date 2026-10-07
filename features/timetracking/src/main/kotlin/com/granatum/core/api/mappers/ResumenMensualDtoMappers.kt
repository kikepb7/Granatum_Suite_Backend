package com.granatum.core.api.mappers

import com.granatum.core.api.dto.DiaResumenDto
import com.granatum.core.api.dto.ResumenMensualDto
import com.granatum.core.domain.model.DiaResumen
import com.granatum.core.domain.model.ResumenMensual

fun ResumenMensual.toDto(): ResumenMensualDto = ResumenMensualDto(
    empleadoId = empleadoId,
    anio = anio,
    mes = mes,
    tipoContrato = tipoContrato,
    totalMinutosTrabajados = totalMinutosTrabajados,
    dias = dias.map { it.toDto() }
)

fun DiaResumen.toDto(): DiaResumenDto = DiaResumenDto(
    fecha = fecha,
    entrada = entrada,
    salida = salida,
    minutosTrabajados = minutosTrabajados,
    minutosPausa = minutosPausa,
    reconstruido = reconstruido,
    corregido = corregido
)
