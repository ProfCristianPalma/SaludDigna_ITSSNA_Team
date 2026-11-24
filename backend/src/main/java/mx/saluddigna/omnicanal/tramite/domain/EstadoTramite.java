package mx.saluddigna.omnicanal.tramite.domain;

/** Maquina de estados del tramite. Toda transicion pasa por TramiteService y deja un evento. */
public enum EstadoTramite {
    INICIADO,
    DATOS_PENDIENTES,
    COTIZADO,
    CLINICA_SELECCIONADA,
    CONFIRMADO,
    ATENDIDO
}
