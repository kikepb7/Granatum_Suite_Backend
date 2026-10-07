-- The retention purge now also removes records of exports whose covered range
-- has been fully purged (specs/003-timetracking-export, D-013), and its own
-- immutable log has to say how many.
--
-- A new migration rather than an edit to V11: V11 is already applied on `main`,
-- and constitution principle II forbids editing an applied migration.
--
-- DEFAULT 0 keeps every earlier purge row true: those runs removed no exports
-- because the table did not exist yet.
ALTER TABLE depuraciones_retencion
    ADD COLUMN exportaciones_eliminadas INTEGER NOT NULL DEFAULT 0
        CONSTRAINT ck_depuraciones_exportaciones_no_negativo
            CHECK (exportaciones_eliminadas >= 0);
