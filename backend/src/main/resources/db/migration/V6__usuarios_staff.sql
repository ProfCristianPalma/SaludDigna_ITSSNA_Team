-- V6: cuentas del personal en Postgres.
-- Antes el staff (asesor/admin) vivía hardcodeado en AuthController
-- (USUARIOS_STAFF, contraseñas en texto plano en el código, fuera de
-- la BD y sin CRUD). Ahora vive aquí, con hash PBKDF2, y se siembra
-- desde DataSeeder (no hay ningún hash hardcodeado en este SQL).
CREATE TABLE IF NOT EXISTS usuarios_staff (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nombre VARCHAR(255) NOT NULL,
    usuario VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    rol VARCHAR(30) NOT NULL CHECK (rol IN ('ASESOR', 'ADMINISTRADOR')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
