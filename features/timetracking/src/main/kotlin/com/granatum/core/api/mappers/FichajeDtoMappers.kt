package com.granatum.core.api.mappers

import com.granatum.core.domain.model.FichajeModel
import com.granatum.core.domain.model.PausaModel
import com.granatum.core.domain.model.UbicacionModel
import com.granatum.core.api.dto.FichajeDto
import com.granatum.core.api.dto.PausaDto
import com.granatum.core.api.dto.UbicacionDto

/**
 * Model -> Dto.
 *
 * [incluirUbicacion] exists for the REPRESENTANTE role: article 34.9 entitles
 * worker representatives to the register, but not to where each person clocked
 * in, which is the most intrusive datum in it. Showing it would be processing
 * personal data with no obligation behind it.
 *
 * The flag drops the fields rather than nulling them, because null already
 * means "recorded without a location" and reusing it here would make the two
 * cases indistinguishable.
 */
fun FichajeModel.toDto(incluirUbicacion: Boolean = true): FichajeDto = FichajeDto(
    id = id,
    empleadoId = empleadoId,
    entrada = entrada,
    salida = salida,
    estado = estado,
    minutosTrabajados = minutosTrabajados,
    fueIncompleto = fueIncompleto,
    ubicacionEntrada = if (incluirUbicacion) ubicacionEntrada?.toDto() else null,
    ubicacionSalida = if (incluirUbicacion) ubicacionSalida?.toDto() else null,
    pausas = pausas.map { it.toDto() }
)

fun PausaModel.toDto(): PausaDto = PausaDto(
    id = id,
    tipo = tipo,
    inicio = inicio,
    fin = fin
)

fun UbicacionModel.toDto(): UbicacionDto = UbicacionDto(
    latitud = latitud,
    longitud = longitud,
    precisionMetros = precisionMetros
)
