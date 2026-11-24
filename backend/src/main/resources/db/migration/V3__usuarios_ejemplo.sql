-- V3: 4 usuarios ejemplo adicionales (Ana ya esta en V2).
-- ON CONFLICT DO NOTHING para que sea seguro en bases que ya tienen V2.
-- Los tramites de cada uno los crea DataSeeder en distintos estados,
-- para que el asesor y el Journey tengan casos que mostrar.
INSERT INTO pacientes (id, nombre, telefono, email, fecha_nacimiento, created_at) VALUES
    (gen_random_uuid(), 'Luis Hernandez Torres', '2221112233', 'luis.demo@saluddigna.test', '1985-03-22', NOW()),
    (gen_random_uuid(), 'Maria Fernanda Ruiz', '2224445566', 'maria.demo@saluddigna.test', '1992-08-09', NOW()),
    (gen_random_uuid(), 'Carlos Mendoza Vega', '2387778899', 'carlos.demo@saluddigna.test', '1978-11-30', NOW()),
    (gen_random_uuid(), 'Fernanda Lopez Diaz', '2229990011', 'fernanda.demo@saluddigna.test', '2001-06-17', NOW())
ON CONFLICT (telefono) DO NOTHING;
