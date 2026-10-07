package com.granatum.core.service

import com.granatum.core.domain.exception.EmpresaSinConfigurarException
import com.granatum.core.domain.exception.NifInvalidoException
import com.granatum.core.infrastructure.claude.ConfiguracionClaude
import com.granatum.core.infrastructure.database.entities.EmpresaEntity
import com.granatum.core.infrastructure.database.repositories.EmpresaRepository
import com.granatum.core.validation.NifValidator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** The company's own data (FR-029). */
data class DatosEmpresa(
    val razonSocial: String,
    val nif: String,
    val nifNormalizado: String,
    /** Whether invoices are read by Claude or filled in by hand (research.md D-006). */
    val reconocimientoActivo: Boolean
) {
    override fun toString(): String = "DatosEmpresa(reconocimientoActivo=$reconocimientoActivo)"
}

@Service
class EmpresaService(
    private val empresas: EmpresaRepository,
    private val claude: ConfiguracionClaude,
    private val clock: Clock = Clock.systemUTC()
) {
    @Transactional(readOnly = true)
    fun consultar(): DatosEmpresa = buscar() ?: throw EmpresaSinConfigurarException(alConsultar = true)

    /** For the services that need it: null if not configured yet. */
    @Transactional(readOnly = true)
    fun buscar(): DatosEmpresa? = empresas.findById(1).map { it.aDatos() }.orElse(null)

    @Transactional
    fun guardar(razonSocial: String, nif: String, autor: UUID): DatosEmpresa {
        val normalizado = NifValidator.normalizarFiscal(nif)
        if (!NifValidator.esValido(normalizado)) throw NifInvalidoException()

        val fila = empresas.findById(1).orElse(null)
        val guardada = if (fila == null) {
            empresas.save(EmpresaEntity(1, razonSocial.trim(), nif.trim(), normalizado, autor, clock.instant()))
        } else {
            fila.razonSocial = razonSocial.trim()
            fila.nif = nif.trim()
            fila.nifNormalizado = normalizado
            fila.actualizadaPor = autor
            fila.actualizadaEn = clock.instant()
            fila
        }
        return guardada.aDatos()
    }

    private fun EmpresaEntity.aDatos() = DatosEmpresa(razonSocial, nif, nifNormalizado, claude.activo)
}
