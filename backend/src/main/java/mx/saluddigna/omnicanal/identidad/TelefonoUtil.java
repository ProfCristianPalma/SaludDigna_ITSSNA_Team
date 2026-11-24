package mx.saluddigna.omnicanal.identidad;

/**
 * Normalización de teléfonos a 10 dígitos, sin lada.
 *
 * Regla (momentánea, pedida por el usuario): todos los teléfonos se
 * guardan y se comparan a 10 dígitos, sin +52. Se quitan espacios,
 * guiones y paréntesis, y el prefijo +52 / 0052 / 52 al inicio cuando
 * el número tiene más de 10 dígitos. Ejemplos:
 * "+52 222 000 0001" → "2220000001", "522220000001" → "2220000001",
 * "2220000001" → "2220000001" (sin cambios).
 *
 * Importante: el teléfono se compara como string exacto en la BD
 * (PacienteRepository.findByTelefono), así que sin normalizar,
 * "+52 222 000 0001" y "2220000001" eran pacientes distintos y los
 * folios se iban a un duplicado. Todas las búsquedas y registros
 * pasan por aquí, en backend y en el front (api.ts normalizarTelefono).
 */
public final class TelefonoUtil {

    private TelefonoUtil() {
    }

    public static String normalizar(String telefono) {
        if (telefono == null) {
            return null;
        }
        String digitos = telefono.replaceAll("[^0-9]", "");
        if (digitos.isEmpty()) {
            return digitos;
        }
        // Solo se quita el prefijo si sobran dígitos: un número de
        // 10 dígitos que empiece por 52 no se toca.
        while (digitos.length() > 10) {
            if (digitos.startsWith("0052")) {
                digitos = digitos.substring(4);
            } else if (digitos.startsWith("52")) {
                digitos = digitos.substring(2);
            } else {
                break;
            }
        }
        return digitos;
    }
}
