-- V10: dos estudios nuevos del catálogo (2026-11-24).
-- Electrocardiograma $450 / 20 min y Mastografía $950 / 30 min.
-- Se insertan por migración (no en V2, que ya está aplicada) para que
-- el backend les asigne sus ids (5 y 6, en este orden) y el front use
-- esos mismos en el mock (MOCK_ESTUDIOS): si los ids no coinciden, la
-- parrilla vuelve a dar 400 como con 'est-001'. El DataSeeder también
-- los siembra en el perfil H2 (cuando estudios está vacío).
-- ON CONFLICT DO NOTHING: si el catálogo ya los trae (p. ej. una
-- siembra manual previa), no se duplican.
INSERT INTO estudios (nombre, descripcion, precio, duracion_min) VALUES
    ('Electrocardiograma', 'Electrocardiograma de 12 derivaciones', 450.00, 20),
    ('Mastografía', 'Mastografía bilateral', 950.00, 30)
ON CONFLICT DO NOTHING;
