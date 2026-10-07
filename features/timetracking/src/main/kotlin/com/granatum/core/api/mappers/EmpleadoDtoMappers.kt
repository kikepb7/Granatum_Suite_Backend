package com.granatum.core.api.mappers

import com.granatum.core.api.dto.EmpleadoDto
import com.granatum.core.domain.model.EmpleadoModel

/**
 * Model -> Dto.
 *
 * This is the one DTO that carries the identity document, because the resource
 * is the person's own record and an ADMIN managing staff needs to see it. It
 * must not leak into fichaje responses, where it has no business being.
 */
fun EmpleadoModel.toDto(): EmpleadoDto = EmpleadoDto(
    id = id,
    nombre = nombre,
    documentoIdentidad = documentoIdentidad,
    puesto = puesto,
    tipoContrato = tipoContrato,
    fechaAlta = fechaAlta,
    activo = activo
)
