package com.granatum.core.service

import com.granatum.core.domain.exception.CompanyNotConfiguredException
import com.granatum.core.domain.exception.InvalidNifException
import com.granatum.core.infrastructure.claude.InvoiceClaudeSettings
import com.granatum.core.infrastructure.database.entities.CompanyEntity
import com.granatum.core.infrastructure.database.repositories.CompanyRepository
import com.granatum.core.validation.NifValidator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** The company's own data (FR-029). */
data class CompanyData(
    val legalName: String,
    val nif: String,
    val normalizedNif: String,
    /** Whether invoices are read by Claude or filled in by hand (research.md D-006). */
    val recognitionActive: Boolean
) {
    override fun toString(): String = "CompanyData(recognitionActive=$recognitionActive)"
}

@Service
class CompanyService(
    private val companies: CompanyRepository,
    private val claude: InvoiceClaudeSettings,
    private val clock: Clock = Clock.systemUTC()
) {
    @Transactional(readOnly = true)
    fun get(): CompanyData = find() ?: throw CompanyNotConfiguredException(onRead = true)

    /** For the services that need it: null if not configured yet. */
    @Transactional(readOnly = true)
    fun find(): CompanyData? = companies.findById(1).map { it.toData() }.orElse(null)

    @Transactional
    fun save(legalName: String, nif: String, author: UUID): CompanyData {
        val normalized = NifValidator.normalizeTaxId(nif)
        if (!NifValidator.isValid(normalized)) throw InvalidNifException()

        val row = companies.findById(1).orElse(null)
        val saved = if (row == null) {
            companies.save(CompanyEntity(1, legalName.trim(), nif.trim(), normalized, author, clock.instant()))
        } else {
            row.legalName = legalName.trim()
            row.nif = nif.trim()
            row.normalizedNif = normalized
            row.updatedBy = author
            row.updatedAt = clock.instant()
            row
        }
        return saved.toData()
    }

    private fun CompanyEntity.toData() = CompanyData(legalName, nif, normalizedNif, claude.active)
}
