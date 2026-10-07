package com.granatum.core

import com.granatum.core.domain.model.EstadoFactura
import com.granatum.core.domain.model.PropuestaReconocida
import com.granatum.core.service.EmpresaService
import com.granatum.core.service.FacturaLecturas
import com.granatum.core.service.FicheroSubido
import com.granatum.core.service.SubidaFacturas
import java.util.UUID
import javax.sql.DataSource

/**
 * Builds invoices for integration tests through the real upload path, with the
 * recognition double answering what each test needs.
 */
class EscenarioFacturas(
    private val subida: SubidaFacturas,
    private val lecturas: FacturaLecturas,
    private val falso: ReconocedorFalso,
    private val empresa: EmpresaService,
    private val dataSource: DataSource
) {
    val admin: UUID = UUID.randomUUID()

    fun configurarEmpresa() = empresa.guardar("Floristeria Granatum S.L.", "B12345674", admin)

    /** A draft recognised from a fresh file, with a unique number unless told otherwise. */
    fun borrador(propuesta: PropuestaReconocida = ReconocedorFalso.propuesta(numero = "F-${UUID.randomUUID().toString().take(8)}")): UUID {
        falso.guion = { ReconocedorFalso.reconocida(propuesta) }
        val id = subida.subir(listOf(FicheroSubido(null, Muestras.pdf())), admin).single().facturaId!!
        esperar(id, EstadoFactura.BORRADOR)
        return id
    }

    fun esperar(id: UUID, estado: EstadoFactura) {
        val limite = System.nanoTime() + 10_000_000_000
        while (System.nanoTime() < limite) {
            if (lecturas.estado(id) == estado) return
            Thread.sleep(30)
        }
        throw AssertionError("la factura no llego a $estado; esta en ${lecturas.estado(id)}")
    }

    fun texto(sql: String, vararg parametros: Any): String? = dataSource.connection.use { c ->
        c.prepareStatement(sql).use { s ->
            parametros.forEachIndexed { i, p -> s.setObject(i + 1, p) }
            s.executeQuery().use { r -> if (r.next()) r.getString(1) else null }
        }
    }

    fun ejecutar(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
}
