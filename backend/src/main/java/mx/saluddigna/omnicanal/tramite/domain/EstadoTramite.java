package mx.saluddigna.omnicanal.tramite.domain;

/** Maquina de estados del tramite. Toda transicion pasa por TramiteService y deja un evento. */
public enum EstadoTramite {
    INICIADO,
    DATOS_PENDIENTES,
    COTIZADO,
    CLINICA_SELECCIONADA,
    CONFIRMADO,
    ATENDIDO,
    /** Terminal: el paciente no se presento a su cita confirmada. */
    NO_PRESENTADO,
    /** Terminal: el estudio no se pudo realizar (el doctor apunta el motivo en el log). */
    NO_REALIZADO
}
