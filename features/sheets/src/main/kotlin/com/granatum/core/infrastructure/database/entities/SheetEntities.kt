package com.granatum.core.infrastructure.database.entities

import com.granatum.core.domain.model.SheetTaskErrorReason
import com.granatum.core.domain.model.SheetTaskKind
import com.granatum.core.domain.model.SheetTaskStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

/** The two spreadsheets: one row, `id = 1` (research.md D-007). */
@Entity
@Table(name = "sheet_settings")
class SheetSettingsEntity(
    @Id
    val id: Short = 1,

    @Column(name = "shifts_spreadsheet_id", length = 100)
    var shiftsSpreadsheetId: String?,

    @Column(name = "invoices_spreadsheet_id", length = 100)
    var invoicesSpreadsheetId: String?,

    @Column(name = "configured_by", nullable = false)
    var configuredBy: UUID,

    @Column(name = "configured_at", nullable = false)
    var configuredAt: Instant,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
) {
    override fun equals(other: Any?): Boolean = this === other || (other is SheetSettingsEntity && id == other.id)
    override fun hashCode(): Int = javaClass.hashCode()
    override fun toString(): String = "SheetSettingsEntity(id=$id)"
}

/**
 * A queued write (research.md D-003). No @Version: every change to a task is
 * made under a row lock (`FOR UPDATE`), which the coalescing upsert also takes.
 */
@Entity
@Table(name = "sheet_tasks")
class SheetTaskEntity(
    @Id
    val id: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    val kind: SheetTaskKind,

    @Column(name = "reference", nullable = false, length = 64)
    val reference: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: SheetTaskStatus,

    @Column(name = "attempts", nullable = false)
    var attempts: Int,

    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: Instant,

    @Column(name = "last_error", length = 300)
    var lastError: String?,

    @Column(name = "requested_by")
    var requestedBy: UUID?,

    @Column(name = "row_count")
    var rowCount: Int?,

    @Column(name = "last_names_pending")
    var lastNamesPending: Int?,

    @Column(name = "repeat_requested", nullable = false)
    var repeatRequested: Boolean,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,

    @Column(name = "completed_at")
    var completedAt: Instant?,

    /** Why the last attempt failed (V38); null if it did not. */
    @Enumerated(EnumType.STRING)
    @Column(name = "error_reason", length = 30)
    var errorReason: SheetTaskErrorReason? = null
) {
    override fun equals(other: Any?): Boolean = this === other || (other is SheetTaskEntity && id == other.id)
    override fun hashCode(): Int = javaClass.hashCode()
    override fun toString(): String = "SheetTaskEntity(id=$id, kind=$kind, status=$status)"
}

/** Which tab a written invoice is in (research.md D-004). */
@Entity
@Table(name = "sheet_invoice_rows")
class SheetInvoiceRowEntity(
    @Id
    @Column(name = "invoice_id")
    val invoiceId: UUID,

    @Column(name = "spreadsheet_id", nullable = false, length = 100)
    var spreadsheetId: String,

    @Column(name = "tab", nullable = false, length = 7)
    var tab: String,

    @Column(name = "written_at", nullable = false)
    var writtenAt: Instant
) {
    override fun equals(other: Any?): Boolean = this === other || (other is SheetInvoiceRowEntity && invoiceId == other.invoiceId)
    override fun hashCode(): Int = javaClass.hashCode()
    override fun toString(): String = "SheetInvoiceRowEntity(invoiceId=$invoiceId, tab=$tab)"
}
