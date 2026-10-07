-- Invoicing (feature 004): invoices, their VAT lines and the original files.

CREATE TABLE facturas (
    id                      UUID          PRIMARY KEY,
    estado                  VARCHAR(20)   NOT NULL,
    tipo                    VARCHAR(10),
    emisor_nombre           VARCHAR(200),
    emisor_nif              VARCHAR(20),
    emisor_nif_normalizado  VARCHAR(20),
    destinatario_nombre     VARCHAR(200),
    destinatario_nif        VARCHAR(20),
    numero                  VARCHAR(60),
    fecha_emision           DATE,
    concepto                VARCHAR(500),
    moneda                  CHAR(3)       NOT NULL DEFAULT 'EUR',
    retenciones             NUMERIC(12,2) NOT NULL DEFAULT 0,
    total                   NUMERIC(12,2),
    rectificativa           BOOLEAN       NOT NULL DEFAULT FALSE,
    documento_sha256        VARCHAR(64)   NOT NULL,
    subida_por              UUID          NOT NULL,
    subida_en               TIMESTAMPTZ   NOT NULL,
    confirmada_por          UUID,
    confirmada_en           TIMESTAMPTZ,
    descartada_por          UUID,
    descartada_en           TIMESTAMPTZ,
    version                 INTEGER       NOT NULL,
    CONSTRAINT ck_facturas_estado
        CHECK (estado IN ('PENDIENTE_RECONOCER', 'BORRADOR', 'CONFIRMADA', 'DESCARTADA')),
    CONSTRAINT ck_facturas_tipo CHECK (tipo IS NULL OR tipo IN ('EMITIDA', 'RECIBIDA')),
    CONSTRAINT ck_facturas_huella CHECK (char_length(documento_sha256) = 64),
    -- The database refuses an incomplete confirmed invoice too (FR-013): the
    -- service gives the readable reason, this is the guarantee.
    CONSTRAINT ck_facturas_confirmada_completa CHECK (
        estado <> 'CONFIRMADA' OR (
            tipo IS NOT NULL AND emisor_nif IS NOT NULL AND numero IS NOT NULL
            AND fecha_emision IS NOT NULL AND total IS NOT NULL AND moneda = 'EUR'
        )
    ),
    CONSTRAINT ck_facturas_confirmada_en CHECK ((estado = 'CONFIRMADA') = (confirmada_en IS NOT NULL)),
    CONSTRAINT ck_facturas_descartada_en CHECK ((estado = 'DESCARTADA') = (descartada_en IS NOT NULL))
);

-- Duplicates (research.md D-015), guaranteed here and not only in the service,
-- where two simultaneous requests would both read "not there" first:
-- the same file cannot be live twice...
CREATE UNIQUE INDEX uk_facturas_documento_vivo
    ON facturas (documento_sha256) WHERE estado <> 'DESCARTADA';
-- ...and the same invoice cannot be confirmed twice, even from two photos.
CREATE UNIQUE INDEX uk_facturas_confirmada
    ON facturas (emisor_nif_normalizado, numero, fecha_emision) WHERE estado = 'CONFIRMADA';

CREATE INDEX idx_facturas_fecha_emision ON facturas (fecha_emision);
CREATE INDEX idx_facturas_estado ON facturas (estado);
CREATE INDEX idx_facturas_emisor ON facturas (emisor_nif_normalizado);

-- One or more per invoice: an invoice can carry several VAT rates (FR-003).
CREATE TABLE factura_lineas_iva (
    id               UUID          PRIMARY KEY,
    factura_id       UUID          NOT NULL REFERENCES facturas (id),
    orden            SMALLINT      NOT NULL,
    tipo_iva         NUMERIC(5,2)  NOT NULL,
    base             NUMERIC(12,2) NOT NULL,
    cuota            NUMERIC(12,2) NOT NULL,
    recargo          NUMERIC(12,2) NOT NULL DEFAULT 0,
    causa_sin_cuota  VARCHAR(30),
    CONSTRAINT ck_lineas_tipo_iva CHECK (tipo_iva BETWEEN 0 AND 100),
    CONSTRAINT ck_lineas_causa CHECK (
        causa_sin_cuota IS NULL
        OR causa_sin_cuota IN ('EXENTA', 'INVERSION_SUJETO_PASIVO', 'INTRACOMUNITARIA')
    )
);

CREATE INDEX idx_lineas_factura ON factura_lineas_iva (factura_id, orden);

-- The original, exactly as uploaded (FR-002, FR-025). Its own table so that
-- listing invoices never reads megabytes (research.md D-008).
CREATE TABLE factura_documentos (
    factura_id       UUID         PRIMARY KEY REFERENCES facturas (id),
    contenido        BYTEA        NOT NULL,
    media_type       VARCHAR(40)  NOT NULL,
    tamano           INTEGER      NOT NULL,
    -- May contain a person's name: stored for the download, never logged.
    nombre_original  VARCHAR(255),
    CONSTRAINT ck_documentos_media_type
        CHECK (media_type IN ('image/jpeg', 'image/png', 'image/webp', 'application/pdf')),
    CONSTRAINT ck_documentos_tamano CHECK (tamano BETWEEN 1 AND 10485760)
);

ALTER TABLE facturas ENABLE ROW LEVEL SECURITY;
ALTER TABLE factura_lineas_iva ENABLE ROW LEVEL SECURITY;
ALTER TABLE factura_documentos ENABLE ROW LEVEL SECURITY;
