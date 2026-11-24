package mx.saluddigna.omnicanal.tramite;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Cita para el tablero de Clínica: DTO plano, sin entidades JPA.
 *
 * Antes la recepción devolvía entidades Tramite con paciente LAZY y el
 * front solo veía un error genérico si algo fallaba al serializar. El
 * DTO se construye dentro de la transacción del servicio, cargando
 * explícitamente paciente, estudio y clínica desde sus repositorios,
 * así que la respuesta siempre es JSON plano y completo.
 */
public record CitaClinicaDTO(
        String folio,
        String tramiteId,
        String pacienteNombre,
        String pacienteTelefono,
        String estudioNombre,
        BigDecimal precio,
        String clinicaNombre,
        String clinicaCiudad,
        LocalDateTime fechaCita,
        String estado) {
}
