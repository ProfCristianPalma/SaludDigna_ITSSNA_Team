-- V9: eliminar trámites ACTIVOS duplicados por (paciente, estudio).
-- Un paciente solo puede tener UN trámite activo por estudio (regla
-- desde 2026-11-24: al cotizar un estudio que ya tiene activo, el
-- backend devuelve 409 con el folio existente para retomarlo, en vez
-- de crear otro). Pero en la BD viva ya hay duplicados de las pruebas
-- (p. ej. Ana tenía 2 folios de Ultrasonido abdominal COTIZADO,
-- SD-2026-0001 y SD-2026-0007): esta migración los limpia una vez.
--
-- De cada grupo duplicado se conserva UN solo trámite: el de estado
-- más avanzado (CONFIRMADO > CLINICA_SELECCIONADA > COTIZADO >
-- DATOS_PENDIENTES > INICIADO) y, en empate, el más reciente
-- (created_at DESC, folio DESC como desempate determinista). Los
-- demás se eliminan con sus dependientes: primero sus citas (citas.
-- tramite_id tiene FK a tramites), luego sus eventos (eventos.
-- tramite_id es columna suelta sin FK, se borran por limpieza) y al
-- final los trámites.
--
-- Seguridad:
-- - Solo tocan trámites ACTIVOS (INICIADO, DATOS_PENDIENTES,
--   COTIZADO, CLINICA_SELECCIONADA, CONFIRMADO) con estudio asignado
--   (estudio_id NOT NULL). Los terminales (ATENDIDO, NO_PRESENTADO,
--   NO_REALIZADO) NUNCA se tocan: repetir un estudio ya completado
--   es historial legítimo, no un duplicado.
-- - En una BD fresca (sin duplicados) es un no-op: no borra nada.
-- - No se toca ninguna migración ya aplicada (V1–V8 intocables).
WITH duplicados AS (
    SELECT id
    FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY paciente_id, estudio_id
                   ORDER BY CASE estado
                                WHEN 'CONFIRMADO' THEN 4
                                WHEN 'CLINICA_SELECCIONADA' THEN 3
                                WHEN 'COTIZADO' THEN 2
                                WHEN 'DATOS_PENDIENTES' THEN 1
                                ELSE 0
                            END DESC,
                            created_at DESC,
                            folio DESC
               ) AS rn
        FROM tramites
        WHERE estudio_id IS NOT NULL
          AND estado IN ('INICIADO', 'DATOS_PENDIENTES', 'COTIZADO', 'CLINICA_SELECCIONADA', 'CONFIRMADO')
    ) ranked
    WHERE rn > 1
)
DELETE FROM citas WHERE tramite_id IN (SELECT id FROM duplicados);

WITH duplicados AS (
    SELECT id
    FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY paciente_id, estudio_id
                   ORDER BY CASE estado
                                WHEN 'CONFIRMADO' THEN 4
                                WHEN 'CLINICA_SELECCIONADA' THEN 3
                                WHEN 'COTIZADO' THEN 2
                                WHEN 'DATOS_PENDIENTES' THEN 1
                                ELSE 0
                            END DESC,
                            created_at DESC,
                            folio DESC
               ) AS rn
        FROM tramites
        WHERE estudio_id IS NOT NULL
          AND estado IN ('INICIADO', 'DATOS_PENDIENTES', 'COTIZADO', 'CLINICA_SELECCIONADA', 'CONFIRMADO')
    ) ranked
    WHERE rn > 1
)
DELETE FROM eventos WHERE tramite_id IN (SELECT id FROM duplicados);

WITH duplicados AS (
    SELECT id
    FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY paciente_id, estudio_id
                   ORDER BY CASE estado
                                WHEN 'CONFIRMADO' THEN 4
                                WHEN 'CLINICA_SELECCIONADA' THEN 3
                                WHEN 'COTIZADO' THEN 2
                                WHEN 'DATOS_PENDIENTES' THEN 1
                                ELSE 0
                            END DESC,
                            created_at DESC,
                            folio DESC
               ) AS rn
        FROM tramites
        WHERE estudio_id IS NOT NULL
          AND estado IN ('INICIADO', 'DATOS_PENDIENTES', 'COTIZADO', 'CLINICA_SELECCIONADA', 'CONFIRMADO')
    ) ranked
    WHERE rn > 1
)
DELETE FROM tramites WHERE id IN (SELECT id FROM duplicados);
