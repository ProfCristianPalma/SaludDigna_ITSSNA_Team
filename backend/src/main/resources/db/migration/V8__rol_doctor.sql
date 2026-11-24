-- V8: permitir el rol DOCTOR en usuarios_staff.
-- El rol DOCTOR se agregó en el código (commit 387bb58: DataSeeder siembra
-- doctor/doctor123, AdminStaffService y SecurityConfig lo aceptan), pero la
-- CHECK de V6 solo permitía ASESOR y ADMINISTRADOR. Al arrancar, el
-- DataSeeder intentaba insertar al doctor, PostgreSQL rechazaba la fila
-- (violates check constraint "usuarios_staff_rol_check") y el backend se
-- caía antes de escuchar en el 8080, así que /api/health no respondía.
-- Flyway aplica esta migración sola al arrancar el backend, sin down -v.
-- Es solo un cambio de constraint: no toca datos ni otras tablas.
ALTER TABLE usuarios_staff DROP CONSTRAINT IF EXISTS usuarios_staff_rol_check;

ALTER TABLE usuarios_staff ADD CONSTRAINT usuarios_staff_rol_check
    CHECK (rol IN ('ASESOR', 'ADMINISTRADOR', 'DOCTOR'));
