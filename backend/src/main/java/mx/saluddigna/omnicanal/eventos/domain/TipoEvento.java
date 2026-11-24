package mx.saluddigna.omnicanal.eventos.domain;

/** Tipos de evento del log append-only. El estado del tramite se deriva de estos eventos. */
public final class TipoEvento {

    public static final String PACIENTE_REGISTRADO = "paciente.registrado";
    public static final String TRAMITE_INICIADO = "tramite.iniciado";
    public static final String COTIZACION_CREADA = "cotizacion.creada";
    public static final String CLINICA_SELECCIONADA = "clinica.seleccionada";
    public static final String IDENTIDAD_VERIFICADA = "identidad.verificada";
    public static final String CANAL_CAMBIADO = "canal.cambiado";
    public static final String DATO_PENDIENTE_COMPLETADO = "dato.pendiente.completado";
    public static final String CITA_CONFIRMADA = "cita.confirmada";
    public static final String ATENCION_CONCLUIDA = "atencion.concluida";
    public static final String TRAMITE_RECUPERADO = "tramite.recuperado";
    /** El paciente llego a la clinica (check-in, sin cambiar el estado). */
    public static final String CHECK_IN = "checkin.registrado";
    /** El paciente no se presento a su cita confirmada (estado NO_PRESENTADO). */
    public static final String NO_PRESENTADO = "cita.no_presentado";
    /** El estudio no se pudo realizar; el motivo va en el payload (estado NO_REALIZADO). */
    public static final String NO_REALIZADO = "cita.no_realizado";
    /** Un asesor edito los datos del paciente (nombre/telefono/fecha/email). */
    public static final String PACIENTE_ACTUALIZADO = "paciente.actualizado";
    /** Un asesor/admin restableció la contraseña del paciente (el payload NUNCA lleva la contraseña). */
    public static final String PASSWORD_RESTABLECIDA = "password.restablecida";

    private TipoEvento() {
    }
}
