-- V4: contraseña del paciente para login/registro reales.
-- Flyway aplica esta migracion sola al arrancar el backend, sin down -v.
-- El hash se guarda con PBKDF2 (ver PasswordService); nunca en texto plano.
-- Las cuentas creadas antes de V4 quedan con password_hash NULL hasta que
-- DataSeeder les asigne la contraseña demo o el paciente complete su registro.
ALTER TABLE pacientes ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255);
