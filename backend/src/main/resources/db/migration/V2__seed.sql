-- V2: catalogo y paciente demo. El tramite demo de Ana lo crea
-- DataSeeder (por el servicio, para que el log de eventos quede completo).
INSERT INTO estudios (nombre, descripcion, precio, duracion_min) VALUES
    ('Ultrasonido abdominal', 'Ultrasonido de abdomen completo', 850.00, 30),
    ('Perfil tiroideo', 'Perfil tiroideo en sangre (TSH, T3, T4)', 620.00, 15),
    ('Radiografia torax', 'Radiografia de torax PA y lateral', 450.00, 20),
    ('Analisis sangre', 'Biometria hematica completa', 380.00, 10)
ON CONFLICT DO NOTHING;

INSERT INTO clinicas (nombre, ciudad, direccion, telefono) VALUES
    ('Clinica Puebla Centro', 'Puebla', 'Av. Juarez 123', '+52 222 000 0000'),
    ('Clinica Salud Digna Tehuacan', 'Tehuacan', 'Calle 5 Sur 45', '+52 238 000 0000')
ON CONFLICT DO NOTHING;

INSERT INTO pacientes (id, nombre, telefono, email, fecha_nacimiento, created_at) VALUES
    (gen_random_uuid(), 'Ana Garcia Lopez', '+52 222 000 0001', 'ana.demo@saluddigna.test', '1990-05-14', NOW())
ON CONFLICT (telefono) DO NOTHING;
