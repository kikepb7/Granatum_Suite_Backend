-- Invoicing (feature 004) in English (docs/english-naming.md): tables, columns,
-- constraints and indexes renamed, stored values translated, CHECKs rewritten.
-- No data is lost: every row stays, its values translated in place. V17-V19
-- are not edited (principle II). RLS is a property of each table and survives
-- the rename (principle VII).

-- 1. Constraints and partial indexes that name old values go first: the values
-- change below, and a partial unique index rebuilt over the old predicate
-- could see discarded rows as live ones.
ALTER TABLE trimestre_eventos DROP CONSTRAINT ck_trimestre_eventos_accion;
ALTER TABLE trimestre_eventos DROP CONSTRAINT ck_trimestre_eventos_motivo;
ALTER TABLE trimestre_eventos DROP CONSTRAINT ck_trimestre_eventos_totales;
ALTER TABLE facturas DROP CONSTRAINT ck_facturas_estado;
ALTER TABLE facturas DROP CONSTRAINT ck_facturas_tipo;
ALTER TABLE facturas DROP CONSTRAINT ck_facturas_confirmada_completa;
ALTER TABLE facturas DROP CONSTRAINT ck_facturas_confirmada_en;
ALTER TABLE facturas DROP CONSTRAINT ck_facturas_descartada_en;
DROP INDEX uk_facturas_documento_vivo;
DROP INDEX uk_facturas_confirmada;
ALTER TABLE factura_lineas_iva DROP CONSTRAINT ck_lineas_causa;
ALTER TABLE factura_reconocimientos DROP CONSTRAINT ck_reconocimientos_resultado;
ALTER TABLE factura_cambios DROP CONSTRAINT ck_cambios_accion;

-- 2. Tables.
ALTER TABLE empresa RENAME TO companies;
ALTER TABLE trimestres RENAME TO quarters;
ALTER TABLE trimestre_eventos RENAME TO quarter_events;
ALTER TABLE facturas RENAME TO invoices;
ALTER TABLE factura_lineas_iva RENAME TO invoice_vat_lines;
ALTER TABLE factura_documentos RENAME TO invoice_documents;
ALTER TABLE factura_reconocimientos RENAME TO invoice_recognitions;
ALTER TABLE factura_cambios RENAME TO invoice_changes;

-- 3. Columns.
ALTER TABLE companies RENAME COLUMN razon_social TO legal_name;
ALTER TABLE companies RENAME COLUMN nif_normalizado TO normalized_nif;
ALTER TABLE companies RENAME COLUMN actualizada_por TO updated_by;
ALTER TABLE companies RENAME COLUMN actualizada_en TO updated_at;

ALTER TABLE quarters RENAME COLUMN anio TO year;
ALTER TABLE quarters RENAME COLUMN trimestre TO quarter;
ALTER TABLE quarters RENAME COLUMN cerrado TO closed;

ALTER TABLE quarter_events RENAME COLUMN anio TO year;
ALTER TABLE quarter_events RENAME COLUMN trimestre TO quarter;
ALTER TABLE quarter_events RENAME COLUMN accion TO action;
ALTER TABLE quarter_events RENAME COLUMN motivo TO reason;
ALTER TABLE quarter_events RENAME COLUMN totales TO totals;
ALTER TABLE quarter_events RENAME COLUMN autor_id TO author_id;
ALTER TABLE quarter_events RENAME COLUMN ocurrido_en TO occurred_at;

ALTER TABLE invoices RENAME COLUMN estado TO status;
ALTER TABLE invoices RENAME COLUMN tipo TO type;
ALTER TABLE invoices RENAME COLUMN emisor_nombre TO issuer_name;
ALTER TABLE invoices RENAME COLUMN emisor_nif TO issuer_nif;
ALTER TABLE invoices RENAME COLUMN emisor_nif_normalizado TO issuer_normalized_nif;
ALTER TABLE invoices RENAME COLUMN destinatario_nombre TO recipient_name;
ALTER TABLE invoices RENAME COLUMN destinatario_nif TO recipient_nif;
ALTER TABLE invoices RENAME COLUMN numero TO number;
ALTER TABLE invoices RENAME COLUMN fecha_emision TO issue_date;
ALTER TABLE invoices RENAME COLUMN concepto TO concept;
ALTER TABLE invoices RENAME COLUMN moneda TO currency;
ALTER TABLE invoices RENAME COLUMN retenciones TO withholdings;
ALTER TABLE invoices RENAME COLUMN rectificativa TO corrective;
ALTER TABLE invoices RENAME COLUMN documento_sha256 TO document_sha256;
ALTER TABLE invoices RENAME COLUMN subida_por TO uploaded_by;
ALTER TABLE invoices RENAME COLUMN subida_en TO uploaded_at;
ALTER TABLE invoices RENAME COLUMN confirmada_por TO confirmed_by;
ALTER TABLE invoices RENAME COLUMN confirmada_en TO confirmed_at;
ALTER TABLE invoices RENAME COLUMN descartada_por TO discarded_by;
ALTER TABLE invoices RENAME COLUMN descartada_en TO discarded_at;

ALTER TABLE invoice_vat_lines RENAME COLUMN factura_id TO invoice_id;
ALTER TABLE invoice_vat_lines RENAME COLUMN orden TO sort_order;
ALTER TABLE invoice_vat_lines RENAME COLUMN tipo_iva TO vat_rate;
ALTER TABLE invoice_vat_lines RENAME COLUMN cuota TO vat_amount;
ALTER TABLE invoice_vat_lines RENAME COLUMN recargo TO surcharge;
ALTER TABLE invoice_vat_lines RENAME COLUMN causa_sin_cuota TO no_vat_cause;

ALTER TABLE invoice_documents RENAME COLUMN factura_id TO invoice_id;
ALTER TABLE invoice_documents RENAME COLUMN contenido TO content;
ALTER TABLE invoice_documents RENAME COLUMN tamano TO size_bytes;
ALTER TABLE invoice_documents RENAME COLUMN nombre_original TO original_name;

ALTER TABLE invoice_recognitions RENAME COLUMN factura_id TO invoice_id;
ALTER TABLE invoice_recognitions RENAME COLUMN resultado TO result;
ALTER TABLE invoice_recognitions RENAME COLUMN modelo TO model;
ALTER TABLE invoice_recognitions RENAME COLUMN propuesta TO proposal;
ALTER TABLE invoice_recognitions RENAME COLUMN campos_dudosos TO uncertain_fields;
ALTER TABLE invoice_recognitions RENAME COLUMN tokens_entrada TO input_tokens;
ALTER TABLE invoice_recognitions RENAME COLUMN tokens_salida TO output_tokens;
ALTER TABLE invoice_recognitions RENAME COLUMN creado_en TO created_at;

ALTER TABLE invoice_changes RENAME COLUMN factura_id TO invoice_id;
ALTER TABLE invoice_changes RENAME COLUMN accion TO action;
ALTER TABLE invoice_changes RENAME COLUMN valores_anteriores TO previous_values;
ALTER TABLE invoice_changes RENAME COLUMN autor_id TO author_id;
ALTER TABLE invoice_changes RENAME COLUMN ocurrido_en TO occurred_at;

-- 4. Stored values.
UPDATE quarter_events SET action = CASE action WHEN 'CIERRE' THEN 'CLOSE' WHEN 'REAPERTURA' THEN 'REOPEN' ELSE action END;

UPDATE invoices SET status = CASE status
    WHEN 'PENDIENTE_RECONOCER' THEN 'PENDING_RECOGNITION'
    WHEN 'BORRADOR' THEN 'DRAFT'
    WHEN 'CONFIRMADA' THEN 'CONFIRMED'
    WHEN 'DESCARTADA' THEN 'DISCARDED'
    ELSE status END;
UPDATE invoices SET type = CASE type WHEN 'EMITIDA' THEN 'ISSUED' WHEN 'RECIBIDA' THEN 'RECEIVED' ELSE type END
 WHERE type IS NOT NULL;

UPDATE invoice_vat_lines SET no_vat_cause = CASE no_vat_cause
    WHEN 'EXENTA' THEN 'EXEMPT'
    WHEN 'INVERSION_SUJETO_PASIVO' THEN 'REVERSE_CHARGE'
    WHEN 'INTRACOMUNITARIA' THEN 'INTRA_COMMUNITY'
    ELSE no_vat_cause END
 WHERE no_vat_cause IS NOT NULL;

UPDATE invoice_recognitions SET result = CASE result
    WHEN 'RECONOCIDA' THEN 'RECOGNIZED'
    WHEN 'NO_ES_FACTURA' THEN 'NOT_AN_INVOICE'
    WHEN 'VARIAS_FACTURAS' THEN 'MULTIPLE_INVOICES'
    WHEN 'RECHAZADA' THEN 'REFUSED'
    ELSE result END;
-- The kind of failure (`REFUSAL`, `REFUSAL:<category>`, `TIMEOUT` and
-- `MAX_TOKENS` were already English).
UPDATE invoice_recognitions SET error = CASE error
    WHEN 'ERROR_API' THEN 'API_ERROR'
    WHEN 'ERROR_RED' THEN 'NETWORK_ERROR'
    WHEN 'RESPUESTA_INVALIDA' THEN 'INVALID_RESPONSE'
    WHEN 'PARADA_INESPERADA' THEN 'UNEXPECTED_STOP'
    WHEN 'ERROR_INTERNO' THEN 'INTERNAL_ERROR'
    ELSE error END
 WHERE error IS NOT NULL;
UPDATE invoice_recognitions SET model = 'unknown' WHERE model = 'desconocido';

UPDATE invoice_changes SET action = CASE action
    WHEN 'CORRECCION' THEN 'CORRECTION'
    WHEN 'DESCARTE' THEN 'DISCARD'
    WHEN 'RECLASIFICACION' THEN 'RECLASSIFICATION'
    ELSE action END;

-- 5. The JSONB documents (a recognition's proposal and doubtful fields, an
-- invoice's previous values, a close's totals) carry the field names of the
-- code that wrote them: renamed the same way, at any depth, and the enum
-- values under `status`, `type` and `noVatCause` translated. Helpers dropped
-- at the end.
CREATE FUNCTION v31_invoices_json_keys() RETURNS jsonb LANGUAGE sql IMMUTABLE AS $$
    SELECT '{
        "estado": "status", "tipo": "type", "emisor": "issuer", "destinatario": "recipient", "nombre": "name",
        "numero": "number", "fechaEmision": "issueDate", "concepto": "concept", "moneda": "currency",
        "lineas": "vatLines", "tipoIva": "vatRate", "cuota": "vatAmount", "recargo": "surcharge",
        "causaSinCuota": "noVatCause", "retenciones": "withholdings", "rectificativa": "corrective",
        "esFactura": "isInvoice", "variasFacturas": "multipleInvoices", "emisorNombre": "issuerName",
        "emisorNif": "issuerNif", "destinatarioNombre": "recipientName", "destinatarioNif": "recipientNif",
        "camposDudosos": "uncertainFields", "emitidas": "issued", "recibidas": "received", "facturas": "invoices",
        "ivaPorTipo": "vatByRate", "sinCuota": "noVatBase", "EXENTA": "EXEMPT",
        "INVERSION_SUJETO_PASIVO": "REVERSE_CHARGE", "INTRACOMUNITARIA": "INTRA_COMMUNITY"
    }'::jsonb
$$;

CREATE FUNCTION v31_invoices_json_values() RETURNS jsonb LANGUAGE sql IMMUTABLE AS $$
    SELECT '{
        "PENDIENTE_RECONOCER": "PENDING_RECOGNITION", "BORRADOR": "DRAFT", "CONFIRMADA": "CONFIRMED",
        "DESCARTADA": "DISCARDED", "EMITIDA": "ISSUED", "RECIBIDA": "RECEIVED", "EXENTA": "EXEMPT",
        "INVERSION_SUJETO_PASIVO": "REVERSE_CHARGE", "INTRACOMUNITARIA": "INTRA_COMMUNITY"
    }'::jsonb
$$;

-- A list of field names of the proposal (`camposDudosos`): its elements are
-- renamed, not its keys.
CREATE FUNCTION v31_invoices_rename_field_list(j jsonb) RETURNS jsonb LANGUAGE sql IMMUTABLE AS $$
    SELECT COALESCE(jsonb_agg(to_jsonb(COALESCE(v31_invoices_json_keys() ->> (e.value #>> '{}'), e.value #>> '{}'))
                              ORDER BY e.ord), '[]'::jsonb)
      FROM jsonb_array_elements(j) WITH ORDINALITY AS e(value, ord)
$$;

CREATE FUNCTION v31_invoices_rename_json(j jsonb) RETURNS jsonb LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    renamed jsonb;
    k text;
    v jsonb;
    new_key text;
BEGIN
    IF j IS NULL THEN
        RETURN NULL;
    ELSIF jsonb_typeof(j) = 'object' THEN
        renamed := '{}'::jsonb;
        FOR k, v IN SELECT key, value FROM jsonb_each(j) LOOP
            new_key := COALESCE(v31_invoices_json_keys() ->> k, k);
            IF new_key IN ('status', 'type', 'noVatCause') AND jsonb_typeof(v) = 'string' THEN
                v := to_jsonb(COALESCE(v31_invoices_json_values() ->> (v #>> '{}'), v #>> '{}'));
            ELSIF new_key = 'uncertainFields' AND jsonb_typeof(v) = 'array' THEN
                v := v31_invoices_rename_field_list(v);
            ELSE
                v := v31_invoices_rename_json(v);
            END IF;
            renamed := renamed || jsonb_build_object(new_key, v);
        END LOOP;
        RETURN renamed;
    ELSIF jsonb_typeof(j) = 'array' THEN
        SELECT COALESCE(jsonb_agg(v31_invoices_rename_json(e.value) ORDER BY e.ord), '[]'::jsonb)
          INTO renamed
          FROM jsonb_array_elements(j) WITH ORDINALITY AS e(value, ord);
        RETURN renamed;
    END IF;
    RETURN j;
END
$$;

UPDATE invoice_recognitions SET proposal = v31_invoices_rename_json(proposal) WHERE proposal IS NOT NULL;
UPDATE invoice_recognitions SET uncertain_fields = v31_invoices_rename_field_list(uncertain_fields)
 WHERE uncertain_fields IS NOT NULL AND jsonb_typeof(uncertain_fields) = 'array';
UPDATE invoice_changes SET previous_values = v31_invoices_rename_json(previous_values);
UPDATE quarter_events SET totals = v31_invoices_rename_json(totals) WHERE totals IS NOT NULL;

DROP FUNCTION v31_invoices_rename_json(jsonb);
DROP FUNCTION v31_invoices_rename_field_list(jsonb);
DROP FUNCTION v31_invoices_json_values();
DROP FUNCTION v31_invoices_json_keys();

-- 6. CHECKs and partial indexes again, over the new names and values.
ALTER TABLE quarter_events
    ADD CONSTRAINT ck_quarter_events_action CHECK (action IN ('CLOSE', 'REOPEN')),
    ADD CONSTRAINT ck_quarter_events_reason CHECK ((action = 'REOPEN') = (reason IS NOT NULL)),
    ADD CONSTRAINT ck_quarter_events_totals CHECK ((action = 'CLOSE') = (totals IS NOT NULL));

ALTER TABLE invoices
    ADD CONSTRAINT ck_invoices_status
        CHECK (status IN ('PENDING_RECOGNITION', 'DRAFT', 'CONFIRMED', 'DISCARDED')),
    ADD CONSTRAINT ck_invoices_type CHECK (type IS NULL OR type IN ('ISSUED', 'RECEIVED')),
    ADD CONSTRAINT ck_invoices_confirmed_complete CHECK (
        status <> 'CONFIRMED' OR (
            type IS NOT NULL AND issuer_nif IS NOT NULL AND number IS NOT NULL
            AND issue_date IS NOT NULL AND total IS NOT NULL AND currency = 'EUR'
        )
    ),
    ADD CONSTRAINT ck_invoices_confirmed_at CHECK (status <> 'CONFIRMED' OR confirmed_at IS NOT NULL),
    ADD CONSTRAINT ck_invoices_discarded_at CHECK ((status = 'DISCARDED') = (discarded_at IS NOT NULL));

CREATE UNIQUE INDEX uk_invoices_live_document
    ON invoices (document_sha256) WHERE status <> 'DISCARDED';
CREATE UNIQUE INDEX uk_invoices_confirmed
    ON invoices (issuer_normalized_nif, number, issue_date) WHERE status = 'CONFIRMED';

ALTER TABLE invoice_vat_lines ADD CONSTRAINT ck_invoice_vat_lines_no_vat_cause CHECK (
    no_vat_cause IS NULL OR no_vat_cause IN ('EXEMPT', 'REVERSE_CHARGE', 'INTRA_COMMUNITY')
);

ALTER TABLE invoice_recognitions ADD CONSTRAINT ck_invoice_recognitions_result CHECK (
    result IN ('RECOGNIZED', 'NOT_AN_INVOICE', 'MULTIPLE_INVOICES', 'REFUSED', 'ERROR')
);

ALTER TABLE invoice_changes ADD CONSTRAINT ck_invoice_changes_action
    CHECK (action IN ('CORRECTION', 'DISCARD', 'RECLASSIFICATION'));

-- 7. The remaining constraints and indexes, only renamed: they name no value.
ALTER TABLE companies RENAME CONSTRAINT empresa_pkey TO companies_pkey;
ALTER TABLE companies RENAME CONSTRAINT ck_empresa_fila_unica TO ck_companies_single_row;

ALTER TABLE quarters RENAME CONSTRAINT trimestres_pkey TO quarters_pkey;
ALTER TABLE quarters RENAME CONSTRAINT ck_trimestres_anio TO ck_quarters_year;
ALTER TABLE quarters RENAME CONSTRAINT ck_trimestres_trimestre TO ck_quarters_quarter;

ALTER TABLE quarter_events RENAME CONSTRAINT trimestre_eventos_pkey TO quarter_events_pkey;
ALTER TABLE quarter_events RENAME CONSTRAINT fk_trimestre_eventos_trimestre TO fk_quarter_events_quarter;
ALTER INDEX idx_trimestre_eventos_trimestre RENAME TO idx_quarter_events_quarter;

ALTER TABLE invoices RENAME CONSTRAINT facturas_pkey TO invoices_pkey;
ALTER TABLE invoices RENAME CONSTRAINT ck_facturas_huella TO ck_invoices_fingerprint;
ALTER INDEX idx_facturas_fecha_emision RENAME TO idx_invoices_issue_date;
ALTER INDEX idx_facturas_estado RENAME TO idx_invoices_status;
ALTER INDEX idx_facturas_emisor RENAME TO idx_invoices_issuer;

ALTER TABLE invoice_vat_lines RENAME CONSTRAINT factura_lineas_iva_pkey TO invoice_vat_lines_pkey;
ALTER TABLE invoice_vat_lines RENAME CONSTRAINT factura_lineas_iva_factura_id_fkey TO invoice_vat_lines_invoice_id_fkey;
ALTER TABLE invoice_vat_lines RENAME CONSTRAINT ck_lineas_tipo_iva TO ck_invoice_vat_lines_vat_rate;
ALTER INDEX idx_lineas_factura RENAME TO idx_invoice_vat_lines_invoice;

ALTER TABLE invoice_documents RENAME CONSTRAINT factura_documentos_pkey TO invoice_documents_pkey;
ALTER TABLE invoice_documents RENAME CONSTRAINT factura_documentos_factura_id_fkey TO invoice_documents_invoice_id_fkey;
ALTER TABLE invoice_documents RENAME CONSTRAINT ck_documentos_media_type TO ck_invoice_documents_media_type;
ALTER TABLE invoice_documents RENAME CONSTRAINT ck_documentos_tamano TO ck_invoice_documents_size;

ALTER TABLE invoice_recognitions RENAME CONSTRAINT factura_reconocimientos_pkey TO invoice_recognitions_pkey;
ALTER TABLE invoice_recognitions RENAME CONSTRAINT factura_reconocimientos_factura_id_fkey TO invoice_recognitions_invoice_id_fkey;
ALTER INDEX idx_reconocimientos_factura RENAME TO idx_invoice_recognitions_invoice;

ALTER TABLE invoice_changes RENAME CONSTRAINT factura_cambios_pkey TO invoice_changes_pkey;
ALTER TABLE invoice_changes RENAME CONSTRAINT factura_cambios_factura_id_fkey TO invoice_changes_invoice_id_fkey;
ALTER INDEX idx_cambios_factura RENAME TO idx_invoice_changes_invoice;
