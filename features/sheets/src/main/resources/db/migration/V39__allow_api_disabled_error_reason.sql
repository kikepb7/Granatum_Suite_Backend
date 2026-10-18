-- Feature 012: a write can also fail because the Google Sheets API is not
-- enabled in the service account's project. Google answers 403 then, like a
-- spreadsheet that is not shared, but the fix is different (enable the API in
-- the Google Cloud project), so it gets its own reason: API_DISABLED.
--
-- V38 is not edited (principle II): its CHECK is replaced here.
ALTER TABLE sheet_tasks DROP CONSTRAINT ck_sheet_tasks_error_reason;
ALTER TABLE sheet_tasks ADD CONSTRAINT ck_sheet_tasks_error_reason CHECK (error_reason IN (
    'NOT_FOUND', 'NOT_SHARED', 'READ_ONLY', 'BAD_REQUEST', 'API_DISABLED',
    'UNAVAILABLE', 'NO_HANDLER', 'ABORTED', 'TRANSIENT'
));
