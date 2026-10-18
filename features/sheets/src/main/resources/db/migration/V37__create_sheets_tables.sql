-- Feature 012: copy of the shift register and the invoices in Google Sheets
-- (specs/012-google-sheets-sync/data-model.md). V35 and V36 belong to
-- timetracking in the same feature.

-- The two spreadsheets, one row per environment.
CREATE TABLE sheet_settings (
    id                      SMALLINT     PRIMARY KEY,
    shifts_spreadsheet_id   VARCHAR(100),
    invoices_spreadsheet_id VARCHAR(100),
    -- The ADMIN who saved them. No FK, like every reference across features.
    configured_by           UUID         NOT NULL,
    configured_at           TIMESTAMPTZ  NOT NULL,
    version                 BIGINT       NOT NULL,
    CONSTRAINT ck_sheet_settings_single CHECK (id = 1),
    -- Both use one tab per month with the same name (FR-007).
    CONSTRAINT ck_sheet_settings_distinct
        CHECK (shifts_spreadsheet_id IS DISTINCT FROM invoices_spreadsheet_id OR shifts_spreadsheet_id IS NULL)
);

-- The queue of writes (research.md D-003). Operational, not a legal record:
-- the legal trace of an export is in `exports`.
CREATE TABLE sheet_tasks (
    id                 UUID         PRIMARY KEY,
    kind               VARCHAR(20)  NOT NULL,
    -- YYYY-MM for the months, the invoice id for INVOICE.
    reference          VARCHAR(64)  NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    attempts           INT          NOT NULL DEFAULT 0,
    next_attempt_at    TIMESTAMPTZ  NOT NULL,
    -- A readable reason, never credentials nor spreadsheet content.
    last_error         VARCHAR(300),
    -- Who asked for an export or a resync; NULL for the automatic ones.
    requested_by       UUID,
    row_count          INT,
    last_names_pending INT,
    -- A change arrived while the task was RUNNING: run it again when it ends.
    repeat_requested   BOOLEAN      NOT NULL DEFAULT false,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    completed_at       TIMESTAMPTZ,
    CONSTRAINT ck_sheet_tasks_kind CHECK (kind IN ('SHIFT_MONTH', 'INVOICE', 'INVOICE_MONTH')),
    CONSTRAINT ck_sheet_tasks_status CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    CONSTRAINT ck_sheet_tasks_attempts CHECK (attempts >= 0)
);

-- Coalescing: at most one live task per target, so ten changes in a row are
-- one write with the final state.
CREATE UNIQUE INDEX ux_sheet_tasks_live
    ON sheet_tasks (kind, reference)
    WHERE status IN ('PENDING', 'RUNNING');
-- The worker's claim with FOR UPDATE SKIP LOCKED.
CREATE INDEX idx_sheet_tasks_due ON sheet_tasks (status, next_attempt_at);

-- Which tab each written invoice is in: the tab, not the row number, which a
-- manual edit moves (research.md D-004).
CREATE TABLE sheet_invoice_rows (
    invoice_id     UUID         PRIMARY KEY,
    spreadsheet_id VARCHAR(100) NOT NULL,
    tab            VARCHAR(7)   NOT NULL,
    written_at     TIMESTAMPTZ  NOT NULL
);

-- Deny by default to anything that is not the table owner, so Supabase's data
-- API exposes nothing. Never FORCE: the backend connects as the owner.
ALTER TABLE sheet_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE sheet_tasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE sheet_invoice_rows ENABLE ROW LEVEL SECURITY;
