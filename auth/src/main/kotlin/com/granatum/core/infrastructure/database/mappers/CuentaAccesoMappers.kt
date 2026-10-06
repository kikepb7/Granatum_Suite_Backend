package com.granatum.core.infrastructure.database.mappers

import com.granatum.core.domain.model.CuentaAcceso
import com.granatum.core.domain.model.EstadoBloqueo
import com.granatum.core.infrastructure.database.entities.CuentaAccesoEntity
import com.granatum.core.infrastructure.database.entities.EstadoBloqueoEmbeddable

/**
 * Entity -> Model. One direction only, on purpose: entities are created by the
 * services that own the transitions, so a Model -> Entity mapper would be a
 * second way to write the table and would bypass them.
 *
 * `spring.jpa.open-in-view` is false, so every call here has to happen inside
 * the transaction that loaded the entity.
 */
fun CuentaAccesoEntity.toModel(): CuentaAcceso = CuentaAcceso(
    id = id,
    empleadoId = empleadoId,
    email = email,
    rol = rol,
    requiereCambioPassword = requiereCambioPassword,
    estadoBloqueo = estadoBloqueo.toModel()
)

fun EstadoBloqueoEmbeddable.toModel(): EstadoBloqueo = EstadoBloqueo(
    intentosFallidos = intentosFallidos.toInt(),
    nivel = nivelBloqueo.toInt(),
    bloqueadaHasta = bloqueadaHasta
)

/**
 * Writes a computed lockout state back onto the embeddable, in one call.
 *
 * Deliberately all three fields at once and never one at a time: setting
 * `bloqueadaHasta` without `nivelBloqueo` is the mistake that would make every
 * lockout last one minute forever, and no test of a single lockout would catch
 * it.
 */
fun EstadoBloqueoEmbeddable.aplicar(estado: EstadoBloqueo) {
    intentosFallidos = estado.intentosFallidos.toShort()
    nivelBloqueo = estado.nivel.toShort()
    bloqueadaHasta = estado.bloqueadaHasta
}
