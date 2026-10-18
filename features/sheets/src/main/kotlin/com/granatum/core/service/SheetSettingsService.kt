package com.granatum.core.service

import com.granatum.core.domain.exception.InvalidSpreadsheetException
import com.granatum.core.domain.exception.SameSpreadsheetException
import com.granatum.core.domain.exception.SheetsUnreachableException
import com.granatum.core.domain.exception.SpreadsheetNotAccessibleException
import com.granatum.core.domain.exception.SpreadsheetRole
import com.granatum.core.domain.model.SheetSettings
import com.granatum.core.domain.port.SheetsClient
import com.granatum.core.domain.port.SheetsPermanentException
import com.granatum.core.domain.port.SheetsTransientException
import com.granatum.core.domain.port.SheetsUnavailableException
import com.granatum.core.infrastructure.database.entities.SheetSettingsEntity
import com.granatum.core.infrastructure.database.repositories.SheetSettingsRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID

/** What `GET /api/sheets/settings` shows (contracts/README.md). */
data class SheetSettingsView(
    val available: Boolean,
    val serviceAccountEmail: String?,
    val settings: SheetSettings?
)

/**
 * The two spreadsheets (US4, research.md D-007). Saving checks that both can be
 * **edited** - outside any transaction, since it calls Google - and stores them
 * in a short transaction afterwards. Nothing is stored if a check fails or
 * Google does not answer.
 */
@Service
class SheetSettingsService(
    private val settings: SheetSettingsRepository,
    private val client: SheetsClient,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock = Clock.systemUTC()
) {
    companion object {
        private val LINK = Regex("/spreadsheets/d/([A-Za-z0-9_-]+)")
        private val ID = Regex("[A-Za-z0-9_-]{20,100}")

        /** The id from a full link (`…/spreadsheets/d/{id}/edit…`) or a bare id; null if neither. */
        fun spreadsheetId(input: String): String? {
            val text = input.trim()
            val candidate = LINK.find(text)?.groupValues?.get(1) ?: text
            return candidate.takeIf { ID.matches(it) }
        }

        fun url(spreadsheetId: String) = "https://docs.google.com/spreadsheets/d/$spreadsheetId/edit"
    }

    private val transactions = TransactionTemplate(transactionManager)
    private val readOnly = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    fun view(): SheetSettingsView =
        if (!client.active) SheetSettingsView(false, null, null)
        else SheetSettingsView(true, client.serviceAccountEmail(), find())

    /** The configured spreadsheets, for the writers; null before the first save. */
    fun find(): SheetSettings? = readOnly.execute { settings.findById(1).map { it.toModel() }.orElse(null) }

    fun save(shiftsInput: String, invoicesInput: String, author: UUID): SheetSettingsView {
        if (!client.active) throw SheetsUnavailableException()
        val shifts = spreadsheetId(shiftsInput) ?: throw InvalidSpreadsheetException(SpreadsheetRole.SHIFTS)
        val invoices = spreadsheetId(invoicesInput) ?: throw InvalidSpreadsheetException(SpreadsheetRole.INVOICES)
        if (shifts == invoices) throw SameSpreadsheetException()

        check(SpreadsheetRole.SHIFTS, shifts)
        check(SpreadsheetRole.INVOICES, invoices)

        transactions.executeWithoutResult {
            val now = clock.instant()
            val row = settings.findById(1).orElse(null)
            if (row == null) {
                settings.save(SheetSettingsEntity(1, shifts, invoices, author, now))
            } else {
                row.shiftsSpreadsheetId = shifts
                row.invoicesSpreadsheetId = invoices
                row.configuredBy = author
                row.configuredAt = now
            }
        }
        return view()
    }

    private fun check(role: SpreadsheetRole, spreadsheetId: String) {
        try {
            client.checkAccess(spreadsheetId)
        } catch (e: SheetsPermanentException) {
            throw SpreadsheetNotAccessibleException(role, e.reason, client.serviceAccountEmail())
        } catch (e: SheetsTransientException) {
            throw SheetsUnreachableException()
        }
    }

    private fun SheetSettingsEntity.toModel() =
        SheetSettings(shiftsSpreadsheetId, invoicesSpreadsheetId, configuredBy, configuredAt)
}
