package com.granatum.core.service

import com.granatum.core.domain.contract.DirectorioEmpleados
import com.granatum.core.infrastructure.database.repositories.CuentaAccesoRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Finds access accounts whose person no longer exists (FR-029c).
 *
 * ## Why this has to exist
 *
 * The database cannot prevent these rows. `cuentas_acceso.empleado_id` has no
 * foreign key to `empleados`, because that table belongs to `timetracking` and
 * constitution principle I forbids this module from depending on it. Creating an
 * account for nobody is refused up front, but a person can stop existing *after*
 * their account was created, and nothing in the schema would notice. So FR-029c
 * asks for orphans to be **detected**, not only prevented - this is the
 * detection.
 *
 * An orphan cannot sign in either way - `AutenticacionService` refuses any
 * account whose person the directory does not report as active - so this is
 * about keeping the table honest, not about closing a hole.
 *
 * ## Why in batches
 *
 * One `DirectorioEmpleados.existentes(...)` call per page of accounts, never one
 * `estado(...)` per account: a loop over every row would be a guaranteed N+1
 * across a module boundary, invisible from this side of the interface.
 */
@Service
class DeteccionHuerfanasService(
    private val cuentas: CuentaAccesoRepository,
    private val directorio: DirectorioEmpleados
) {

    data class CuentaHuerfana(
        val cuentaId: UUID,
        val empleadoId: UUID,
        val creadaEn: Instant
    )

    @Transactional(readOnly = true)
    fun buscar(): List<CuentaHuerfana> {
        val huerfanas = mutableListOf<CuentaHuerfana>()
        var pagina = cuentas.findAllByOrderByIdAsc(PageRequest.of(0, TAMANO_LOTE))

        while (true) {
            val lote = pagina.content
            val existentes = directorio.existentes(lote.map { it.empleadoId })

            lote.filter { it.empleadoId !in existentes }
                // Deliberately without the email: identifying the row is enough
                // to clean it up, and an address would be personal data in a
                // diagnostic listing that has no use for it.
                .mapTo(huerfanas) { CuentaHuerfana(it.id, it.empleadoId, it.createdAt) }

            if (!pagina.hasNext()) break
            pagina = cuentas.findAllByOrderByIdAsc(pagina.nextPageable())
        }
        return huerfanas
    }

    companion object {
        const val TAMANO_LOTE = 500
    }
}
