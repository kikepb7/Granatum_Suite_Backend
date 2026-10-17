-- Timetracking schema in English (decision of 2026-10-18, docs/english-naming.md).
--
-- Renames every table, column, index and constraint of this module, rewrites
-- the stored enum codes and the CHECKs that list them, and translates the keys
-- and codes inside the JSONB documents. No row is added or lost, and no value
-- that records a fact - who, when, which shift - changes: only the vocabulary
-- in which it is written.
--
-- A new migration rather than edits to V6-V16: those are applied, and
-- principle II forbids editing an applied migration.
--
-- ## The event log (principle III)
--
-- `shift_events` is append-only for the application, and stays so. This
-- migration is a one-off change of representation, not a write path: the
-- occurrence and reception instants, the ids and the HTTP status are untouched.
-- What changes is the label of each operation (ENTRADA -> CLOCK_IN, ...), the
-- request fingerprint derived from that label, and the stored response body,
-- which is replayed verbatim on a resend and therefore has to speak the new
-- API. Without them, a mobile client resending an operation it queued before
-- the deployment would get `409 CLIENT_EVENT_ID_REUSED`, or a body it cannot
-- read.
--
-- The fingerprint is only recomputed where the stored one is provably the old
-- formula over the stored values (`<clientEventId>|<operation>|<occurredAt>`,
-- see ShiftOperationHandler.fingerprintOf). A row that does not match - an
-- instant the client sent with nanoseconds, which Postgres keeps to the
-- microsecond - keeps its value: it could not have been matched before either.
--
-- ## Triggers
--
-- This module enforces immutability in the application (repositories without
-- mutation, principle III) and declares no triggers or PL/pgSQL functions in
-- V6-V16, so there are no function bodies naming the old tables to recreate.
-- The partial unique indexes whose predicates name an enum value are dropped
-- and recreated below, since renaming the value would otherwise leave them
-- guarding nothing.
--
-- RLS is a property of the table and survives the renames; it is not touched.

-- ---------------------------------------------------------------------------
-- Helpers, dropped at the end.
-- ---------------------------------------------------------------------------

-- java.time.Instant#toString for a timestamptz: UTC, seconds always written,
-- the fraction in groups of three digits and only when non-zero.
CREATE FUNCTION v29_instant_text(ts TIMESTAMPTZ) RETURNS TEXT
    LANGUAGE sql IMMUTABLE AS $$
    SELECT to_char(ts AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS')
        || CASE
               WHEN to_char(ts AT TIME ZONE 'UTC', 'US') = '000000' THEN ''
               WHEN right(to_char(ts AT TIME ZONE 'UTC', 'US'), 3) = '000'
                   THEN '.' || left(to_char(ts AT TIME ZONE 'UTC', 'US'), 3)
               ELSE '.' || to_char(ts AT TIME ZONE 'UTC', 'US')
           END
        || 'Z'
$$;

CREATE FUNCTION v29_fingerprint(client_event_id UUID, operation TEXT, occurred_at TIMESTAMPTZ) RETURNS TEXT
    LANGUAGE sql IMMUTABLE AS $$
    SELECT encode(sha256(convert_to(
        client_event_id::text || '|' || operation || '|' || v29_instant_text(occurred_at), 'UTF8')), 'hex')
$$;

-- Translates the keys and the enum codes of the stored JSON documents: the
-- shift values of a correction (clockIn, clockOut, breaks[type, start, end])
-- and the shift DTO replayed by the event log.
CREATE FUNCTION v29_translate_json(doc JSONB) RETURNS JSONB
    LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    result JSONB;
    k      TEXT;
    v      JSONB;
BEGIN
    IF doc IS NULL THEN
        RETURN NULL;
    END IF;

    IF jsonb_typeof(doc) = 'object' THEN
        result := '{}'::jsonb;
        FOR k, v IN SELECT key, value FROM jsonb_each(doc) LOOP
            IF k IN ('estado', 'tipo') AND jsonb_typeof(v) = 'string' THEN
                v := to_jsonb(CASE v #>> '{}'
                    WHEN 'EN_CURSO'   THEN 'IN_PROGRESS'
                    WHEN 'CERRADO'    THEN 'CLOSED'
                    WHEN 'INCOMPLETO' THEN 'INCOMPLETE'
                    WHEN 'COMIDA'     THEN 'MEAL'
                    WHEN 'DESCANSO'   THEN 'REST'
                    WHEN 'OTRO'       THEN 'OTHER'
                    ELSE v #>> '{}'
                END);
            ELSE
                v := v29_translate_json(v);
            END IF;

            result := result || jsonb_build_object(
                CASE k
                    WHEN 'empleadoId'        THEN 'employeeId'
                    WHEN 'entrada'           THEN 'clockIn'
                    WHEN 'salida'            THEN 'clockOut'
                    WHEN 'estado'            THEN 'status'
                    WHEN 'minutosTrabajados' THEN 'workedMinutes'
                    WHEN 'fueIncompleto'     THEN 'wasIncomplete'
                    WHEN 'ubicacionEntrada'  THEN 'clockInLocation'
                    WHEN 'ubicacionSalida'   THEN 'clockOutLocation'
                    WHEN 'latitud'           THEN 'latitude'
                    WHEN 'longitud'          THEN 'longitude'
                    WHEN 'precisionMetros'   THEN 'accuracyMeters'
                    WHEN 'pausas'            THEN 'breaks'
                    WHEN 'tipo'              THEN 'type'
                    WHEN 'inicio'            THEN 'start'
                    WHEN 'fin'               THEN 'end'
                    ELSE k
                END,
                v
            );
        END LOOP;
        RETURN result;
    END IF;

    IF jsonb_typeof(doc) = 'array' THEN
        SELECT coalesce(jsonb_agg(v29_translate_json(e.value) ORDER BY e.ordinality), '[]'::jsonb)
          INTO result
          FROM jsonb_array_elements(doc) WITH ORDINALITY AS e(value, ordinality);
        RETURN result;
    END IF;

    RETURN doc;
END
$$;

-- ---------------------------------------------------------------------------
-- employees (was empleados)
-- ---------------------------------------------------------------------------

ALTER TABLE empleados DROP CONSTRAINT ck_empleados_tipo_contrato;

ALTER TABLE empleados RENAME TO employees;
ALTER TABLE employees RENAME COLUMN nombre TO name;
ALTER TABLE employees RENAME COLUMN documento_identidad TO identity_document;
ALTER TABLE employees RENAME COLUMN puesto TO position;
ALTER TABLE employees RENAME COLUMN tipo_contrato TO contract_type;
ALTER TABLE employees RENAME COLUMN fecha_alta TO hire_date;
ALTER TABLE employees RENAME COLUMN activo TO active;
ALTER TABLE employees RENAME CONSTRAINT empleados_pkey TO employees_pkey;
ALTER TABLE employees RENAME CONSTRAINT uk_empleados_documento TO uk_employees_identity_document;

UPDATE employees
   SET contract_type = CASE contract_type
       WHEN 'JORNADA_COMPLETA' THEN 'FULL_TIME'
       WHEN 'PARCIAL'          THEN 'PART_TIME'
       WHEN 'POR_HORAS'        THEN 'HOURLY'
       ELSE contract_type
   END;

ALTER TABLE employees ADD CONSTRAINT ck_employees_contract_type
    CHECK (contract_type IN ('FULL_TIME', 'PART_TIME', 'HOURLY'));

-- ---------------------------------------------------------------------------
-- shifts (was fichajes)
-- ---------------------------------------------------------------------------

ALTER TABLE fichajes DROP CONSTRAINT ck_fichajes_estado;
DROP INDEX uk_fichajes_empleado_en_curso;
DROP INDEX idx_fichajes_en_curso;

ALTER TABLE fichajes RENAME TO shifts;
ALTER TABLE shifts RENAME COLUMN empleado_id TO employee_id;
ALTER TABLE shifts RENAME COLUMN entrada TO clock_in;
ALTER TABLE shifts RENAME COLUMN salida TO clock_out;
ALTER TABLE shifts RENAME COLUMN estado TO status;
ALTER TABLE shifts RENAME COLUMN minutos_trabajados TO worked_minutes;
ALTER TABLE shifts RENAME COLUMN fue_incompleto TO was_incomplete;
ALTER TABLE shifts RENAME COLUMN ubicacion_entrada_latitud TO clock_in_latitude;
ALTER TABLE shifts RENAME COLUMN ubicacion_entrada_longitud TO clock_in_longitude;
ALTER TABLE shifts RENAME COLUMN ubicacion_entrada_precision_m TO clock_in_accuracy_meters;
ALTER TABLE shifts RENAME COLUMN ubicacion_salida_latitud TO clock_out_latitude;
ALTER TABLE shifts RENAME COLUMN ubicacion_salida_longitud TO clock_out_longitude;
ALTER TABLE shifts RENAME COLUMN ubicacion_salida_precision_m TO clock_out_accuracy_meters;
ALTER TABLE shifts RENAME CONSTRAINT fichajes_pkey TO shifts_pkey;
ALTER TABLE shifts RENAME CONSTRAINT fichajes_empleado_id_fkey TO shifts_employee_id_fkey;
ALTER TABLE shifts RENAME CONSTRAINT ck_fichajes_salida_posterior TO ck_shifts_clock_out_after_clock_in;
ALTER TABLE shifts RENAME CONSTRAINT ck_fichajes_minutos_no_negativos TO ck_shifts_worked_minutes_non_negative;
ALTER INDEX idx_fichajes_empleado_entrada RENAME TO idx_shifts_employee_clock_in;

UPDATE shifts
   SET status = CASE status
       WHEN 'EN_CURSO'   THEN 'IN_PROGRESS'
       WHEN 'CERRADO'    THEN 'CLOSED'
       WHEN 'INCOMPLETO' THEN 'INCOMPLETE'
       ELSE status
   END;

ALTER TABLE shifts ADD CONSTRAINT ck_shifts_status
    CHECK (status IN ('IN_PROGRESS', 'CLOSED', 'INCOMPLETE'));

-- Only one open shift per person: the real guard against a retried clock-in
-- (see V7).
CREATE UNIQUE INDEX uk_shifts_employee_in_progress
    ON shifts (employee_id)
 WHERE status = 'IN_PROGRESS';

CREATE INDEX idx_shifts_in_progress ON shifts (status) WHERE status = 'IN_PROGRESS';

-- ---------------------------------------------------------------------------
-- shift_breaks (was pausas)
-- ---------------------------------------------------------------------------

ALTER TABLE pausas DROP CONSTRAINT ck_pausas_tipo;

ALTER TABLE pausas RENAME TO shift_breaks;
ALTER TABLE shift_breaks RENAME COLUMN fichaje_id TO shift_id;
ALTER TABLE shift_breaks RENAME COLUMN tipo TO type;
-- `end` is reserved in SQL, hence the `_at` pair.
ALTER TABLE shift_breaks RENAME COLUMN inicio TO started_at;
ALTER TABLE shift_breaks RENAME COLUMN fin TO ended_at;
ALTER TABLE shift_breaks RENAME CONSTRAINT pausas_pkey TO shift_breaks_pkey;
ALTER TABLE shift_breaks RENAME CONSTRAINT pausas_fichaje_id_fkey TO shift_breaks_shift_id_fkey;
ALTER TABLE shift_breaks RENAME CONSTRAINT ck_pausas_fin_posterior TO ck_shift_breaks_end_after_start;
ALTER INDEX idx_pausas_fichaje RENAME TO idx_shift_breaks_shift;
-- Its predicate (`ended_at IS NULL`) follows the column rename by itself.
ALTER INDEX uk_pausas_fichaje_abierta RENAME TO uk_shift_breaks_open;

UPDATE shift_breaks
   SET type = CASE type
       WHEN 'COMIDA'   THEN 'MEAL'
       WHEN 'DESCANSO' THEN 'REST'
       WHEN 'OTRO'     THEN 'OTHER'
       ELSE type
   END;

ALTER TABLE shift_breaks ADD CONSTRAINT ck_shift_breaks_type
    CHECK (type IN ('MEAL', 'REST', 'OTHER'));

-- ---------------------------------------------------------------------------
-- shift_correction_requests (was solicitudes_correccion_fichaje)
-- ---------------------------------------------------------------------------

ALTER TABLE solicitudes_correccion_fichaje DROP CONSTRAINT ck_solicitudes_estado;

ALTER TABLE solicitudes_correccion_fichaje RENAME TO shift_correction_requests;
ALTER TABLE shift_correction_requests RENAME COLUMN fichaje_id TO shift_id;
ALTER TABLE shift_correction_requests RENAME COLUMN solicitante_id TO requester_id;
ALTER TABLE shift_correction_requests RENAME COLUMN motivo TO reason;
ALTER TABLE shift_correction_requests RENAME COLUMN valores_propuestos TO proposed_values;
ALTER TABLE shift_correction_requests RENAME COLUMN valores_originales TO original_values;
ALTER TABLE shift_correction_requests RENAME COLUMN estado TO status;
ALTER TABLE shift_correction_requests RENAME COLUMN resuelta_por_id TO resolved_by_id;
ALTER TABLE shift_correction_requests RENAME COLUMN resuelta_en TO resolved_at;
ALTER TABLE shift_correction_requests RENAME COLUMN motivo_resolucion TO resolution_reason;
ALTER TABLE shift_correction_requests RENAME CONSTRAINT solicitudes_correccion_fichaje_pkey TO shift_correction_requests_pkey;
ALTER TABLE shift_correction_requests RENAME CONSTRAINT solicitudes_correccion_fichaje_fichaje_id_fkey TO shift_correction_requests_shift_id_fkey;
ALTER INDEX idx_solicitudes_fichaje_estado RENAME TO idx_shift_correction_requests_shift_status;

UPDATE shift_correction_requests
   SET status = CASE status
           WHEN 'PENDIENTE' THEN 'PENDING'
           WHEN 'APROBADA'  THEN 'APPROVED'
           WHEN 'RECHAZADA' THEN 'REJECTED'
           ELSE status
       END,
       proposed_values = v29_translate_json(proposed_values),
       original_values = v29_translate_json(original_values);

ALTER TABLE shift_correction_requests ADD CONSTRAINT ck_shift_correction_requests_status
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'));

-- ---------------------------------------------------------------------------
-- shift_events (was fichaje_eventos)
-- ---------------------------------------------------------------------------

ALTER TABLE fichaje_eventos DROP CONSTRAINT ck_fichaje_eventos_tipo_operacion;

ALTER TABLE fichaje_eventos RENAME TO shift_events;
ALTER TABLE shift_events RENAME COLUMN empleado_id TO employee_id;
ALTER TABLE shift_events RENAME COLUMN fichaje_id TO shift_id;
ALTER TABLE shift_events RENAME COLUMN tipo_operacion TO operation_type;
ALTER TABLE shift_events RENAME COLUMN huella_peticion TO request_fingerprint;
ALTER TABLE shift_events RENAME COLUMN estado_respuesta TO response_status;
ALTER TABLE shift_events RENAME COLUMN cuerpo_respuesta TO response_body;
ALTER TABLE shift_events RENAME CONSTRAINT fichaje_eventos_pkey TO shift_events_pkey;
ALTER TABLE shift_events RENAME CONSTRAINT uk_fichaje_eventos_client_event_id TO uk_shift_events_client_event_id;
ALTER INDEX idx_fichaje_eventos_fichaje RENAME TO idx_shift_events_shift;
ALTER INDEX idx_fichaje_eventos_occurred RENAME TO idx_shift_events_occurred;

WITH translated AS (
    SELECT id,
           operation_type AS old_operation,
           CASE operation_type
               WHEN 'ENTRADA'      THEN 'CLOCK_IN'
               WHEN 'INICIO_PAUSA' THEN 'BREAK_START'
               WHEN 'FIN_PAUSA'    THEN 'BREAK_END'
               WHEN 'SALIDA'       THEN 'CLOCK_OUT'
               ELSE operation_type
           END AS new_operation
      FROM shift_events
)
UPDATE shift_events e
   SET operation_type = t.new_operation,
       request_fingerprint = CASE
           WHEN e.request_fingerprint = v29_fingerprint(e.client_event_id, t.old_operation, e.occurred_at)
               THEN v29_fingerprint(e.client_event_id, t.new_operation, e.occurred_at)
           ELSE e.request_fingerprint
       END,
       response_body = v29_translate_json(e.response_body)
  FROM translated t
 WHERE t.id = e.id;

ALTER TABLE shift_events ADD CONSTRAINT ck_shift_events_operation_type
    CHECK (operation_type IN ('CLOCK_IN', 'BREAK_START', 'BREAK_END', 'CLOCK_OUT'));

-- ---------------------------------------------------------------------------
-- retention_purges (was depuraciones_retencion)
-- ---------------------------------------------------------------------------
-- Counts and a date only; their CHECKs follow the column renames by themselves.

ALTER TABLE depuraciones_retencion RENAME TO retention_purges;
ALTER TABLE retention_purges RENAME COLUMN ejecutada_en TO executed_at;
ALTER TABLE retention_purges RENAME COLUMN fecha_corte TO cutoff_date;
ALTER TABLE retention_purges RENAME COLUMN fichajes_eliminados TO deleted_shifts;
ALTER TABLE retention_purges RENAME COLUMN pausas_eliminadas TO deleted_breaks;
ALTER TABLE retention_purges RENAME COLUMN eventos_eliminados TO deleted_events;
ALTER TABLE retention_purges RENAME COLUMN solicitudes_eliminadas TO deleted_requests;
ALTER TABLE retention_purges RENAME COLUMN exportaciones_eliminadas TO deleted_exports;
ALTER TABLE retention_purges RENAME CONSTRAINT depuraciones_retencion_pkey TO retention_purges_pkey;
ALTER TABLE retention_purges RENAME CONSTRAINT ck_depuraciones_recuentos_no_negativos TO ck_retention_purges_counts_non_negative;
ALTER TABLE retention_purges RENAME CONSTRAINT ck_depuraciones_exportaciones_no_negativo TO ck_retention_purges_deleted_exports_non_negative;
ALTER INDEX idx_depuraciones_ejecutada RENAME TO idx_retention_purges_executed_at;

-- ---------------------------------------------------------------------------
-- exports (was exportaciones)
-- ---------------------------------------------------------------------------

ALTER TABLE exportaciones DROP CONSTRAINT ck_exportaciones_rol;
ALTER TABLE exportaciones DROP CONSTRAINT ck_exportaciones_alcance;
ALTER TABLE exportaciones DROP CONSTRAINT ck_exportaciones_empleado;

ALTER TABLE exportaciones RENAME TO exports;
ALTER TABLE exports RENAME COLUMN solicitante_id TO requester_id;
ALTER TABLE exports RENAME COLUMN rol_solicitante TO requester_role;
ALTER TABLE exports RENAME COLUMN alcance TO scope;
ALTER TABLE exports RENAME COLUMN empleado_id TO employee_id;
-- `from` and `to` are reserved in SQL.
ALTER TABLE exports RENAME COLUMN desde TO from_date;
ALTER TABLE exports RENAME COLUMN hasta TO to_date;
ALTER TABLE exports RENAME COLUMN generada_en TO generated_at;
ALTER TABLE exports RENAME COLUMN completada TO completed;
ALTER TABLE exports RENAME COLUMN filas TO row_count;
ALTER TABLE exports RENAME COLUMN huella TO fingerprint;
ALTER TABLE exports RENAME CONSTRAINT exportaciones_pkey TO exports_pkey;
ALTER TABLE exports RENAME CONSTRAINT ck_exportaciones_rango TO ck_exports_range;
ALTER TABLE exports RENAME CONSTRAINT ck_exportaciones_filas TO ck_exports_row_count;
ALTER TABLE exports RENAME CONSTRAINT ck_exportaciones_huella TO ck_exports_fingerprint;
ALTER TABLE exports RENAME CONSTRAINT ck_exportaciones_completada TO ck_exports_completed;
ALTER INDEX idx_exportaciones_huella RENAME TO idx_exports_fingerprint;
ALTER INDEX idx_exportaciones_empleado RENAME TO idx_exports_employee;
ALTER INDEX idx_exportaciones_hasta RENAME TO idx_exports_to_date;

UPDATE exports
   SET requester_role = CASE requester_role
           WHEN 'ENCARGADO'     THEN 'MANAGER'
           WHEN 'EMPLEADO'      THEN 'EMPLOYEE'
           WHEN 'REPRESENTANTE' THEN 'REPRESENTATIVE'
           ELSE requester_role
       END,
       scope = CASE scope
           WHEN 'PERSONA'   THEN 'PERSON'
           WHEN 'PLANTILLA' THEN 'STAFF'
           WHEN 'MENSUAL'   THEN 'MONTHLY'
           ELSE scope
       END;

ALTER TABLE exports ADD CONSTRAINT ck_exports_requester_role
    CHECK (requester_role IN ('ADMIN', 'MANAGER', 'EMPLOYEE', 'REPRESENTATIVE'));
ALTER TABLE exports ADD CONSTRAINT ck_exports_scope
    CHECK (scope IN ('PERSON', 'STAFF', 'MONTHLY'));
ALTER TABLE exports ADD CONSTRAINT ck_exports_employee
    CHECK ((scope = 'STAFF') = (employee_id IS NULL));

-- ---------------------------------------------------------------------------

DROP FUNCTION v29_translate_json(JSONB);
DROP FUNCTION v29_fingerprint(UUID, TEXT, TIMESTAMPTZ);
DROP FUNCTION v29_instant_text(TIMESTAMPTZ);
