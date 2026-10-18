-- Feature 012 (US2, research D-006): where an export of the register went.
--
-- `exports` stays INSERT-only (principle III applied to the export log): this
-- is only an ADD COLUMN, and its default is TRUE for every existing row - each
-- one is a CSV file handed over by `/api/shifts/export` or the monthly
-- download. No existing row changes meaning.
--
-- An export to the Google spreadsheet is recorded with
-- destination = 'GOOGLE_SHEETS', scope = 'STAFF' and employee_id NULL (the
-- whole staff), the month as its period, the rows written and the SHA-256 of
-- those rows. Not 'MONTHLY': ck_exports_employee (V29) requires an employee
-- for every scope but STAFF, and MONTHLY is one person's monthly download.
--
-- A new column on an existing table: its RLS was enabled when the table was
-- created and covers the new column as well (principle VII).
ALTER TABLE exports
    ADD COLUMN destination VARCHAR(20) NOT NULL DEFAULT 'CSV',
    ADD CONSTRAINT ck_exports_destination CHECK (destination IN ('CSV', 'GOOGLE_SHEETS'));
