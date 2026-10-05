package com.granatum.core.infrastructure.database.mappers

import com.granatum.core.domain.model.FichajeModel
import com.granatum.core.domain.model.PausaModel
import com.granatum.core.domain.model.UbicacionModel
import com.granatum.core.infrastructure.database.entities.FichajeEntity
import com.granatum.core.infrastructure.database.entities.PausaEntity
import com.granatum.core.infrastructure.database.entities.UbicacionEmbeddable

/**
 * Entity -> Model.
 *
 * Touches `pausas` and `empleado`, both lazy, so this **must** be called inside
 * the transaction. `spring.jpa.open-in-view` is false, so mapping after the
 * service has returned throws LazyInitializationException - which this project
 * has already hit once, with `MaterialEntity.fotos`.
 */
fun FichajeEntity.toModel(): FichajeModel = FichajeModel(
    id = id,
    empleadoId = empleado.id,
    entrada = entrada,
    salida = salida,
    estado = estado,
    minutosTrabajados = minutosTrabajados,
    fueIncompleto = fueIncompleto,
    ubicacionEntrada = ubicacionEntrada?.toModel(),
    ubicacionSalida = ubicacionSalida?.toModel(),
    pausas = pausas.sortedBy { it.inicio }.map { it.toModel() }
)

fun PausaEntity.toModel(): PausaModel = PausaModel(
    id = id,
    tipo = tipo,
    inicio = inicio,
    fin = fin
)

fun UbicacionEmbeddable.toModel(): UbicacionModel = UbicacionModel(
    latitud = latitud,
    longitud = longitud,
    precisionMetros = precisionMetros
)
