package mx.saluddigna.omnicanal.identidad;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validación y normalización de la CURP (Clave Única de Registro de
 * Población), llave omnicanal del paciente desde V7.
 *
 * Formato: 4 letras (apellido paterno + vocal, apellido materno,
 * nombre) + fecha AAMMDD + H/M + código de entidad (2 letras) +
 * 3 consonantes internas + homoclave (letra o dígito + dígito).
 * Ejemplo válido: GALA900514MDFRPN00 (Ana García López, 14/05/1990).
 *
 * La CURP es solo un identificador de demostración en este hackatón:
 * se valida formato, fecha real y entidad, pero NO se verifica contra
 * RENAPO ni se calcula la homoclave oficial.
 */
public final class CurpUtil {

    private CurpUtil() {
    }

    /** Regex del formato CURP (18 caracteres, todo en mayúsculas). */
    public static final Pattern PATRON =
            Pattern.compile("^[A-Z]{4}\\d{6}[HM][A-Z]{2}[A-Z]{3}[A-Z\\d]\\d$");

    /** La misma expresión sin anclas, para buscar una CURP dentro de un texto libre. */
    private static final Pattern PATRON_EN_TEXTO =
            Pattern.compile("[A-Z]{4}\\d{6}[HM][A-Z]{2}[A-Z]{3}[A-Z\\d]\\d");

    /** Códigos de entidad federativa válidos en la CURP (incluye NE = nacido en el extranjero). */
    public static final Set<String> ENTIDADES = Set.of(
            "AS", "BC", "BS", "CC", "CL", "CM", "CS", "CH", "DF", "DG",
            "GR", "GT", "HG", "JC", "MC", "MN", "MS", "NT", "NL", "OC",
            "PL", "QT", "QR", "SP", "SL", "SR", "TC", "TL", "TS", "VZ",
            "YN", "ZS", "NE");

    /** Normaliza: trim + mayúsculas + sin espacios. null se queda en null. */
    public static String normalizar(String curp) {
        if (curp == null) {
            return null;
        }
        return curp.trim().toUpperCase().replaceAll("\\s+", "");
    }

    /** true si, ya normalizada, casa con el regex de formato (sin validar fecha ni entidad). */
    public static boolean esFormatoValido(String curp) {
        String normalizada = normalizar(curp);
        return normalizada != null && PATRON.matcher(normalizada).matches();
    }

    /**
     * Valida formato + fecha real (AAMMDD) + código de entidad.
     * Devuelve vacío si es válida; si no, el motivo en español.
     */
    public static Optional<String> validar(String curp) {
        String normalizada = normalizar(curp);
        if (normalizada == null || normalizada.isBlank()) {
            return Optional.of("La CURP es obligatoria");
        }
        if (normalizada.length() != 18) {
            return Optional.of("La CURP debe tener 18 caracteres");
        }
        if (!PATRON.matcher(normalizada).matches()) {
            return Optional.of("La CURP no tiene un formato válido "
                    + "(4 letras, fecha AAMMDD, H/M, entidad, 3 letras y homoclave)");
        }
        if (fechaDesdeCurp(normalizada) == null) {
            return Optional.of("La fecha de nacimiento de la CURP no es una fecha real");
        }
        String entidad = normalizada.substring(11, 13);
        if (!ENTIDADES.contains(entidad)) {
            return Optional.of("El código de entidad de la CURP no es válido: " + entidad);
        }
        return Optional.empty();
    }

    /** true si validar() no devuelve motivo. */
    public static boolean esValida(String curp) {
        return validar(curp).isEmpty();
    }

    /**
     * Fecha de nacimiento que codifica la CURP (dígitos AAMMDD, posiciones 5-10).
     * El año es ambiguo (dos dígitos): 00-26 se lee como 2000-2026 y 27-99
     * como 1927-1999. Devuelve null si el formato es inválido o la fecha
     * no es real (31/02, mes 13...).
     */
    public static LocalDate fechaDesdeCurp(String curp) {
        String normalizada = normalizar(curp);
        if (normalizada == null || !PATRON.matcher(normalizada).matches()) {
            return null;
        }
        int anio2 = Integer.parseInt(normalizada.substring(4, 6));
        int mes = Integer.parseInt(normalizada.substring(6, 8));
        int dia = Integer.parseInt(normalizada.substring(8, 10));
        int anioActual2 = LocalDate.now().getYear() % 100;
        int anio = anio2 <= anioActual2 ? 2000 + anio2 : 1900 + anio2;
        try {
            return LocalDate.of(anio, mes, dia);
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    /** Busca la primera CURP con formato válido dentro de un texto libre (mayúsculas/minúsculas, con espacios). */
    public static Optional<String> buscarEnTexto(String texto) {
        if (texto == null) {
            return Optional.empty();
        }
        Matcher m = PATRON_EN_TEXTO.matcher(texto.toUpperCase());
        if (m.find()) {
            return Optional.of(m.group());
        }
        return Optional.empty();
    }
}
