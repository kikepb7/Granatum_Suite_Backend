-- Feature 012: why a write to Google failed, as a code the app can act on,
-- next to the readable Spanish `last_error`.
--
-- Set on every failure - also on a transient one that will be retried, so a
-- PENDING task shows why it is waiting - and cleared on success and on a
-- manual retry. NULL means the last attempt did not fail (or there was none).
--
--   NOT_FOUND, NOT_SHARED, READ_ONLY, BAD_REQUEST  Google refused for good
--   UNAVAILABLE   the server has no Google credentials
--   NO_HANDLER    no writer for the task's kind
--   ABORTED       the writer gave up (e.g. the spreadsheet is no longer configured)
--   TRANSIENT     the last attempt failed transiently (retried, or out of attempts)
--
-- A new column on an existing table: its RLS was enabled in V37 and covers it
-- (principle VII). V37 is not edited (principle II).
ALTER TABLE sheet_tasks
    ADD COLUMN error_reason VARCHAR(30) NULL,
    ADD CONSTRAINT ck_sheet_tasks_error_reason CHECK (error_reason IN (
        'NOT_FOUND', 'NOT_SHARED', 'READ_ONLY', 'BAD_REQUEST',
        'UNAVAILABLE', 'NO_HANDLER', 'ABORTED', 'TRANSIENT'
    ));
