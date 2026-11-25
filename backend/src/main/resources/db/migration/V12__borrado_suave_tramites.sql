-- V12: borrado suave de tramites (asesor / Call Center).
--
-- Se agregan dos columnas a `tramites`; NUNCA se borra un tramite en
-- duro con cascada: `eventos.tramite_id` es NOT NULL y el log es
-- append-only, asi que borrar el tramite destruiria su historial,
-- justo lo que se quiere conservar (ediciones, cancelaciones y
-- eliminaciones con su accion y su motivo quedan en el log).
--   eliminado = TRUE  -> el folio sale de TODOS los listados, pero
--   sigue consultable por folio/id (solo lectura, badge "Eliminado")
--   y su Journey / historial quedan intactos.
--   eliminado_motivo -> la causa que escribio el asesor.
-- No se toca ninguna migracion ya aplicada (V1-V11 intocables).
-- Compatible Postgres y H2 (BOOLEAN / TEXT existen en ambos).
ALTER TABLE tramites ADD COLUMN eliminado BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE tramites ADD COLUMN eliminado_motivo TEXT;
