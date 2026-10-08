package com.granatum.core.service

import com.granatum.core.domain.contract.DirectorioRoles
import com.granatum.core.domain.event.AvisoDominio
import com.granatum.core.domain.event.TipoAviso
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.Role
import org.springframework.stereotype.Component

/**
 * Who hears about each kind of notice (feature 008, FR-001 to FR-005,
 * specs/008-notifications/contracts/README.md).
 *
 * Pending requests go to whoever can resolve them, never to whoever made
 * them; everything else goes to the person it concerns.
 */
@Component
class Destinatarios(private val roles: DirectorioRoles) {

    fun de(aviso: AvisoDominio): Set<EntityId> = when (aviso.tipo) {
        TipoAviso.CORRECCION_PENDIENTE, TipoAviso.AUSENCIA_PENDIENTE ->
            roles.empleadosConRol(setOf(Role.ENCARGADO, Role.ADMIN)) - setOfNotNull(aviso.autorId)
        TipoAviso.FICHAJE_SIN_SALIDA, TipoAviso.FICHAJE_INCOMPLETO,
        TipoAviso.CORRECCION_APROBADA, TipoAviso.CORRECCION_RECHAZADA,
        TipoAviso.AUSENCIA_APROBADA, TipoAviso.AUSENCIA_RECHAZADA ->
            setOfNotNull(aviso.titularId)
    }
}
