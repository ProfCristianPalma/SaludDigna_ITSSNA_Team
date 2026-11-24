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

    private TipoEvento() {
    }
}
