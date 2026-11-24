-- V7: CURP como llave omnicanal del paciente; el teléfono pasa a opcional.
-- Flyway aplica esta migración sola al arrancar el backend, sin down -v.
--
-- - pacientes.curp VARCHAR(18), única con índice parcial: varias filas
--   pueden tener curp NULL (pacientes viejos sin CURP), pero dos CURP
--   iguales no pueden coexistir.
-- - pacientes.telefono deja de ser NOT NULL y deja de ser UNIQUE a nivel
--   de BD. La unicidad del teléfono la sigue imponiendo el servicio
--   (PacienteService.registrar/actualizar comprueban existsByTelefono),
--   así que el comportamiento para el usuario no cambia, pero un
--   paciente ya puede existir sin teléfono (registro solo con CURP).
--
-- La unicidad de teléfono en V1 se declaró como UNIQUE inline en la
-- columna, así que en PostgreSQL la constraint se llama
-- pacientes_telefono_key (tabla_columna_key). El DROP es IF EXISTS por
-- si la base se creó con otro nombre de constraint.
-- (Perfil H2: no corre Flyway — ddl-auto=create-drop — así que esta
-- migración solo afecta a PostgreSQL, que es el perfil de la demo.)
ALTER TABLE pacientes ADD COLUMN curp VARCHAR(18);

CREATE UNIQUE INDEX ux_pacientes_curp ON pacientes (curp) WHERE curp IS NOT NULL;

ALTER TABLE pacientes ALTER COLUMN telefono DROP NOT NULL;

ALTER TABLE pacientes DROP CONSTRAINT IF EXISTS pacientes_telefono_key;
