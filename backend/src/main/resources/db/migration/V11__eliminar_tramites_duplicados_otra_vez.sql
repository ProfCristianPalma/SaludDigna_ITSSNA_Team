-- V11: limpiar trámites duplicados OTRA VEZ + borrar los folios
-- vacíos huérfanos que dejó el bug de WhatsApp (2026-11-24).
--
-- (1) Duplicados activos por (paciente, estudio): MISMA regla que V9
-- (V9 ya está aplicada y no se puede re-correr, pero en la BD viva
-- volvieron a aparecer duplicados). De cada grupo se conserva UN solo
-- trámite: el de estado más avanzado (CONFIRMADO >
-- CLINICA_SELECCIONADA > COTIZADO > DATOS_PENDIENTES > INICIADO) y,
-- en empate, el más reciente (created_at DESC, folio DESC). Los demás
-- se eliminan con sus dependientes: primero sus citas (citas.
-- tramite_id tiene FK a tramites), luego sus eventos (eventos.
-- tramite_id es columna suelta sin FK, se borran por limpieza) y al
-- final los trámites.
--
-- (2) Folios vacíos huérfanos: trámites activos SIN estudio
-- (estudio_id IS NULL), en INICIADO o DATOS_PENDIENTES, creados por
-- WhatsApp. El bug: el bot creaba el folio ANTES de elegir el estudio
-- (ramas "iniciar un trámite nuevo" y cotización genérica) y el folio
-- vacío quedaba guardado aunque luego se retomara el trámite
-- existente. Un trámite vacío no tiene estudio, clínica ni fecha: no
-- se pierde nada al borrarlo, con sus citas y eventos.
-- Solo canal WHATSAPP: el trámite semilla de Fernanda (WEB,
-- DATOS_PENDIENTES, con datos pendientes de CURP y orden médica) NO
-- se toca.
--
-- Seguridad (igual que V9):
-- - Los terminales (ATENDIDO, NO_PRESENTADO, NO_REALIZADO) NUNCA se
--   tocan: repetir un estudio ya completado es historial legítimo.
-- - En una BD limpia es un no-op: no borra nada.
-- - No se toca ninguna migración ya aplicada (V1–V10 intocables).
WITH a_eliminar AS (
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
    UNION
    SELECT id
    FROM tramites
    WHERE estudio_id IS NULL
      AND estado IN ('INICIADO', 'DATOS_PENDIENTES')
      AND canal_origen = 'WHATSAPP'
)
DELETE FROM citas WHERE tramite_id IN (SELECT id FROM a_eliminar);

WITH a_eliminar AS (
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
    UNION
    SELECT id
    FROM tramites
    WHERE estudio_id IS NULL
      AND estado IN ('INICIADO', 'DATOS_PENDIENTES')
      AND canal_origen = 'WHATSAPP'
)
DELETE FROM eventos WHERE tramite_id IN (SELECT id FROM a_eliminar);

WITH a_eliminar AS (
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
    UNION
    SELECT id
    FROM tramites
    WHERE estudio_id IS NULL
      AND estado IN ('INICIADO', 'DATOS_PENDIENTES')
      AND canal_origen = 'WHATSAPP'
)
DELETE FROM tramites WHERE id IN (SELECT id FROM a_eliminar);
