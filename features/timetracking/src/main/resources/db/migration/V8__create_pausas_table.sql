-- Breaks within a fichaje. Recording them is a product decision, not an
-- article 34.9 obligation, so a day with no pausas is a perfectly valid record.
CREATE TABLE pausas (
    id          UUID        PRIMARY KEY,
    fichaje_id  UUID        NOT NULL REFERENCES fichajes (id),
    tipo        VARCHAR(15) NOT NULL,
    inicio      TIMESTAMPTZ NOT NULL,
    fin         TIMESTAMPTZ,
    CONSTRAINT ck_pausas_tipo CHECK (tipo IN ('COMIDA', 'DESCANSO', 'OTRO')),
    CONSTRAINT ck_pausas_fin_posterior CHECK (fin IS NULL OR fin > inicio)
);

CREATE INDEX idx_pausas_fichaje ON pausas (fichaje_id);

-- At most one open pausa per fichaje, enforced in the engine for the same
-- concurrency reason as the fichaje index above. Overlap between already-closed
-- pausas can only be introduced by approving a correction, which is a
-- deliberate low-frequency operation, so that case is validated in the service
-- where a readable error can be produced.
CREATE UNIQUE INDEX uk_pausas_fichaje_abierta
    ON pausas (fichaje_id)
 WHERE fin IS NULL;

ALTER TABLE pausas ENABLE ROW LEVEL SECURITY;
