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
    /** El bot ofreció las horas libres de un día (payload {"fecha":"AAAA-MM-DD"}):
     * así una hora suelta después ("10", "10:00") se entiende para ese día. */
    public static final String HORAS_OFRECIDAS = "horas.ofrecidas";
    /** El bot no entendió un mensaje de WhatsApp (payload {"mensaje":"<texto crudo>"}):
     * queda en el log para revisar después qué escriben los pacientes.
     * Solo se registra cuando hay un trámite en contexto: el evento
     * necesita un tramite_id, así que los "no entendí" sin folio (sin
     * trámites activos) no quedan registrados. */
    public static final String MENSAJE_NO_ENTENDIDO = "mensaje.no_entendido";
    /** Un asesor editó un trámite (estudio, clínica, fecha/hora). Payload {"accion":"edicion","nota":"...","cambios":"..."}: la nota es obligatoria. */
    public static final String TRAMITE_EDITADO = "tramite.editado";
    /** Un asesor canceló un trámite. Payload {"accion":"cancelacion","motivo":"..."}: el motivo es obligatorio. */
    public static final String TRAMITE_CANCELADO = "tramite.cancelado";
    /** Un asesor eliminó un trámite (BORRADO SUAVE: solo marca eliminado=true; el trámite y sus eventos se conservan). Payload {"accion":"eliminacion","motivo":"..."}. */
    public static final String TRAMITE_ELIMINADO = "tramite.eliminado";

    private TipoEvento() {
    }
}
