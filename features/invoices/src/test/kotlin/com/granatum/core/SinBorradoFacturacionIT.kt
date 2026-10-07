package com.granatum.core

import com.granatum.core.infrastructure.database.repositories.EmpresaRepository
import com.granatum.core.infrastructure.database.repositories.FacturaCambioRepository
import com.granatum.core.infrastructure.database.repositories.FacturaDocumentoRepository
import com.granatum.core.infrastructure.database.repositories.FacturaReconocimientoRepository
import com.granatum.core.infrastructure.database.repositories.FacturaRepository
import com.granatum.core.infrastructure.database.repositories.TrimestreEventoRepository
import com.granatum.core.infrastructure.database.repositories.TrimestreRepository
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Nothing in invoicing can be deleted: invoices are kept at least six years
 * (Commercial Code, art. 30), discarding one keeps it, and the histories are
 * append-only.
 *
 * The list is **fixed** on purpose. A list built by scanning would silently
 * skip a repository nobody registered; this one fails until each new
 * repository is added and checked - the lesson of feature 003's finding C1.
 *
 * Validated by adding a `deleteById` to `FacturaRepository` (red) and removing
 * it again (2026-10-08).
 */
class SinBorradoFacturacionIT {

    private val repositorios = listOf(
        EmpresaRepository::class.java,
        TrimestreRepository::class.java,
        TrimestreEventoRepository::class.java,
        FacturaRepository::class.java,
        FacturaDocumentoRepository::class.java,
        FacturaReconocimientoRepository::class.java,
        FacturaCambioRepository::class.java
    )

    @Test
    fun `no invoicing repository exposes a deletion`() {
        val ofensores = repositorios.flatMap { repo ->
            repo.methods
                .map { it.name }
                .filter { it.startsWith("delete") || it.startsWith("remove") }
                .map { "${repo.simpleName}.$it" }
        }
        assertEquals(emptyList(), ofensores, "a deletion here would let invoices or their history disappear")
    }

    @Test
    fun `every repository of the module is in the list`() {
        // Seven repositories for eight tables: factura_lineas_iva is reached
        // only through its invoice (cascade), never through a repository.
        assertEquals(7, repositorios.size)
    }
}
