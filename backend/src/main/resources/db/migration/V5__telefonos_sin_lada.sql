-- V5: telefonos a 10 digitos, sin lada (momentaneo, pedido por el usuario).
-- Flyway aplica esta migracion sola al arrancar el backend, sin down -v.
-- El telefono se comparaba como string exacto, asi que "+52 222 000 0001"
-- y "2220000001" eran pacientes distintos y los folios se iban a un
-- duplicado. A partir de V5 todo queda a 10 digitos y el backend
-- normaliza cada telefono que entra (ver TelefonoUtil).
--
-- La normalizacion quita todo lo que no sea digito y, solo si sobran
-- digitos, el prefijo 0052 o 52 inicial. Un numero de 10 digitos que
-- empiece por 52 no se toca.
-- Si existiera un paciente duplicado (misma persona con +52 y sin +52),
-- solo se normaliza la fila mas antigua; la otra queda con su formato
-- viejo para no romper la UNIQUE de telefono. En ese caso revisa la
-- lista en GET /api/pacientes y borra el duplicado sin tramites.
UPDATE pacientes SET telefono = CASE
    WHEN length(regexp_replace(telefono, '[^0-9]', '', 'g')) = 14
        AND regexp_replace(telefono, '[^0-9]', '', 'g') LIKE '0052%'
        THEN substring(regexp_replace(telefono, '[^0-9]', '', 'g') from 5)
    WHEN length(regexp_replace(telefono, '[^0-9]', '', 'g')) = 12
        AND regexp_replace(telefono, '[^0-9]', '', 'g') LIKE '52%'
        THEN substring(regexp_replace(telefono, '[^0-9]', '', 'g') from 3)
    ELSE regexp_replace(telefono, '[^0-9]', '', 'g')
END
WHERE telefono IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM pacientes p2
    WHERE p2.id <> pacientes.id
      AND p2.telefono IS NOT NULL
      AND CASE
        WHEN length(regexp_replace(p2.telefono, '[^0-9]', '', 'g')) = 14
            AND regexp_replace(p2.telefono, '[^0-9]', '', 'g') LIKE '0052%'
            THEN substring(regexp_replace(p2.telefono, '[^0-9]', '', 'g') from 5)
        WHEN length(regexp_replace(p2.telefono, '[^0-9]', '', 'g')) = 12
            AND regexp_replace(p2.telefono, '[^0-9]', '', 'g') LIKE '52%'
            THEN substring(regexp_replace(p2.telefono, '[^0-9]', '', 'g') from 3)
        ELSE regexp_replace(p2.telefono, '[^0-9]', '', 'g')
      END =
      CASE
        WHEN length(regexp_replace(pacientes.telefono, '[^0-9]', '', 'g')) = 14
            AND regexp_replace(pacientes.telefono, '[^0-9]', '', 'g') LIKE '0052%'
            THEN substring(regexp_replace(pacientes.telefono, '[^0-9]', '', 'g') from 5)
        WHEN length(regexp_replace(pacientes.telefono, '[^0-9]', '', 'g')) = 12
            AND regexp_replace(pacientes.telefono, '[^0-9]', '', 'g') LIKE '52%'
            THEN substring(regexp_replace(pacientes.telefono, '[^0-9]', '', 'g') from 3)
        ELSE regexp_replace(pacientes.telefono, '[^0-9]', '', 'g')
      END
      AND p2.created_at < pacientes.created_at
  );

-- Los telefonos de contacto de las clinicas tambien quedan a 10 digitos.
UPDATE clinicas SET telefono = CASE
    WHEN length(regexp_replace(telefono, '[^0-9]', '', 'g')) = 14
        AND regexp_replace(telefono, '[^0-9]', '', 'g') LIKE '0052%'
        THEN substring(regexp_replace(telefono, '[^0-9]', '', 'g') from 5)
    WHEN length(regexp_replace(telefono, '[^0-9]', '', 'g')) = 12
        AND regexp_replace(telefono, '[^0-9]', '', 'g') LIKE '52%'
        THEN substring(regexp_replace(telefono, '[^0-9]', '', 'g') from 3)
    ELSE regexp_replace(telefono, '[^0-9]', '', 'g')
END
WHERE telefono IS NOT NULL;
