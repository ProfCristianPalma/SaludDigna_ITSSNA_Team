package mx.saluddigna.omnicanal.tramite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import mx.saluddigna.omnicanal.catalogo.CitaRepository;
import mx.saluddigna.omnicanal.catalogo.ClinicaRepository;
import mx.saluddigna.omnicanal.catalogo.EstudioRepository;
import mx.saluddigna.omnicanal.catalogo.domain.Cita;
import mx.saluddigna.omnicanal.catalogo.domain.Clinica;
import mx.saluddigna.omnicanal.catalogo.domain.Estudio;
import mx.saluddigna.omnicanal.eventos.EventoService;
import mx.saluddigna.omnicanal.eventos.domain.TipoEvento;
import mx.saluddigna.omnicanal.identidad.CurpUtil;
import mx.saluddigna.omnicanal.identidad.PacienteRepository;
import mx.saluddigna.omnicanal.identidad.TelefonoUtil;
import mx.saluddigna.omnicanal.identidad.domain.Paciente;
import mx.saluddigna.omnicanal.tramite.domain.EstadoTramite;
import mx.saluddigna.omnicanal.tramite.domain.Tramite;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unico lugar que cambia el estado de un tramite.
 * Regla de oro: nunca se escribe un estado sin registrar su evento
 * en el mismo transaction; si el evento falla, el cambio se revierte.
 */
@Service
@Transactional
public class TramiteService {

    private final TramiteRepository tramites;
    private final PacienteRepository pacientes;
    private final EstudioRepository estudios;
    private final ClinicaRepository clinicas;
    private final CitaRepository citas;
    private final EventoService eventos;
    private final ObjectMapper objectMapper;

    public TramiteService(TramiteRepository tramites, PacienteRepository pacientes,
                          EstudioRepository estudios, ClinicaRepository clinicas,
                          CitaRepository citas, EventoService eventos, ObjectMapper objectMapper) {
        this.tramites = tramites;
        this.pacientes = pacientes;
        this.estudios = estudios;
        this.clinicas = clinicas;
        this.citas = citas;
        this.eventos = eventos;
        this.objectMapper = objectMapper;
    }

    public Tramite crearTramite(UUID pacienteId, Long estudioId, String canalOrigen) {
        Paciente paciente = pacientes.findById(pacienteId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Paciente no encontrado: " + pacienteId));
        if (estudioId != null && !estudios.existsById(estudioId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Estudio no encontrado: " + estudioId);
        }
        // Un paciente solo tiene UN trámite activo por estudio: si ya
        // existe uno activo de ese estudio, no se crea otro (409 con
        // el folio existente para retomarlo). Los terminales no
        // cuentan: repetir un estudio ya atendido es válido.
        if (estudioId != null) {
            exigirSinDuplicadoActivo(pacienteId, estudioId, null);
        }
        String canal = canalOrigen == null || canalOrigen.isBlank() ? "WEB" : canalOrigen;
        Tramite tramite = new Tramite(generarFolio(), paciente, estudioId, canal);
        tramite = tramites.save(tramite);
        eventos.registrar(tramite.getId(), TipoEvento.TRAMITE_INICIADO, canal, "PACIENTE",
                json(Map.of("folio", tramite.getFolio(), "estudioId", String.valueOf(estudioId))));
        return tramite;
    }

    /**
     * Borra un tramite VACIO (red de seguridad del bug de WhatsApp
     * 2026-11-24: el bot creaba el folio ANTES de elegir el estudio y
     * el folio vacio quedaba guardado aunque luego se retomara el
     * tramite existente). Solo si esta INICIADO o DATOS_PENDIENTES y
     * SIN estudio, clinica, fecha ni precio: jamas se borra un tramite
     * con datos. Primero sus citas, luego sus eventos y luego el
     * tramite. Devuelve true si lo borro, false si no era vacio (en
     * ese caso no se toca nada).
     */
    public boolean eliminarTramiteVacio(UUID tramiteId) {
        Tramite tramite = buscar(tramiteId);
        if (tramite.getEstudioId() != null || tramite.getClinicaId() != null
                || tramite.getFechaCita() != null || tramite.getPrecioCotizado() != null
                || (tramite.getEstado() != EstadoTramite.INICIADO
                        && tramite.getEstado() != EstadoTramite.DATOS_PENDIENTES)) {
            return false;
        }
        citas.findByTramiteId(tramiteId).ifPresent(citas::delete);
        eventos.eliminarEventosDeTramite(tramiteId);
        tramites.deleteById(tramiteId);
        return true;
    }

    /**
     * Trámite activo de un paciente para un estudio (si existe): un
     * paciente solo puede tener UN trámite activo por estudio. Los
     * terminales (ATENDIDO, NO_PRESENTADO, NO_REALIZADO) no cuentan:
     * repetir un estudio ya cerrado es historial legítimo, no un
     * duplicado. `exceptoTramiteId` excluye al propio trámite que se
     * está cotizando (el flujo Web crea el trámite con el estudio y
     * luego lo cotiza con el mismo estudio).
     */
    @Transactional(readOnly = true)
    public Optional<Tramite> buscarActivoPorEstudio(UUID pacienteId, Long estudioId, UUID exceptoTramiteId) {
        if (pacienteId == null || estudioId == null) return Optional.empty();
        return tramites.findByPacienteIdAndEstadoNotInOrderByCreatedAtDesc(pacienteId, ESTADOS_TERMINALES).stream()
                .filter(t -> estudioId.equals(t.getEstudioId()))
                .filter(t -> exceptoTramiteId == null || !t.getId().equals(exceptoTramiteId))
                .findFirst();
    }

    /** Qué le falta a un trámite activo para completarse: cotizar el
     * estudio (INICIADO/DATOS_PENDIENTES sin estudio ni precio),
     * elegir clínica y confirmar fecha y hora. Un CONFIRMADO no tiene
     * faltantes: solo falta la atención en clínica. */
    public static List<String> faltantes(Tramite tramite) {
        List<String> f = new ArrayList<>();
        if (tramite.getEstudioId() == null || tramite.getPrecioCotizado() == null) f.add("cotizado");
        if (tramite.getClinicaId() == null) f.add("clinica");
        if (tramite.getFechaCita() == null) {
            f.add("fecha");
            f.add("hora");
        }
        return f;
    }

    private static final DateTimeFormatter FORMATO_LATINO_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /**
     * Etiqueta legible de un estado para mostrarle al usuario: nunca el
     * nombre técnico del enum (INICIADO, CLINICA_SELECCIONADA, ...).
     * Para CLINICA_SELECCIONADA se muestra el nombre de la clínica
     * elegida (o "Clínica elegida" si el trámite no la trae).
     */
    public static String etiquetaEstado(EstadoTramite estado, String clinicaNombre) {
        return switch (estado) {
            case INICIADO -> "Iniciado";
            case DATOS_PENDIENTES -> "Datos pendientes";
            case COTIZADO -> "Cotizado";
            case CLINICA_SELECCIONADA -> clinicaNombre != null && !clinicaNombre.isBlank()
                    ? clinicaNombre : "Clínica elegida";
            case CONFIRMADO -> "Cita confirmada";
            case ATENDIDO -> "Atendido";
            case NO_PRESENTADO -> "No se presentó";
            case NO_REALIZADO -> "No se pudo realizar";
            case CANCELADO -> "Cancelado";
        };
    }

    /**
     * Resumen para retomar un trámite activo. Lo comparten el 409 de
     * duplicado (Web/Asesor) y el bot de WhatsApp, así el mensaje es el
     * mismo en todos los canales: folio, estado, estudio, costo,
     * clínica, fecha/hora y qué falta ("cotizado" / "clinica" /
     * "fecha" / "hora"). Si está CONFIRMADO, lo único que falta es la
     * atención en clínica: el mensaje dice que la cita ya está
     * agendada, con toda la información pertinente, y NO ofrece
     * continuar el flujo (yaAgendada=true). Si falta algo, el mensaje
     * pregunta si quiere retomarlo.
     */
    public Map<String, Object> resumenRetomar(Tramite t) {
        String estudioNombre = t.getEstudioId() == null ? "sin estudio aún"
                : estudios.findById(t.getEstudioId()).map(Estudio::getNombre).orElse("ese estudio");
        String clinicaNombre = t.getClinicaId() == null ? null
                : clinicas.findById(t.getClinicaId()).map(Clinica::getNombre).orElse(null);
        List<String> faltan = faltantes(t);
        boolean yaAgendada = t.getEstado() == EstadoTramite.CONFIRMADO;
        // Descripción legible del estado para el mensaje: nunca el
        // nombre técnico del enum. Con clínica elegida se dice el
        // nombre de la clínica ("clínica: Clínica Salud Digna
        // Tehuacán"), no "CLINICA_SELECCIONADA".
        String descripcionEstado = t.getEstado() == EstadoTramite.CLINICA_SELECCIONADA
                ? "clínica: " + (clinicaNombre == null ? "Clínica elegida" : clinicaNombre)
                : etiquetaEstado(t.getEstado(), clinicaNombre);
        String mensaje;
        if (yaAgendada) {
            mensaje = "Ya está agendada tu cita de " + estudioNombre
                    + (t.getPrecioCotizado() != null ? " ($" + t.getPrecioCotizado().toPlainString() + ")" : "")
                    + " en " + (clinicaNombre == null ? "la clínica" : clinicaNombre)
                    + ", el " + (t.getFechaCita() == null ? "sin fecha" : t.getFechaCita().format(FORMATO_LATINO_HORA))
                    + ". Tu folio es " + t.getFolio() + ". No necesitas hacer nada más; te esperamos en la clínica.";
        } else if (t.getEstudioId() == null) {
            mensaje = "Ya tienes un trámite activo (folio " + t.getFolio() + ", " + descripcionEstado
                    + ") y aún no tiene estudio elegido. ¿Quieres retomarlo?";
        } else {
            mensaje = "Ya tienes un trámite activo de " + estudioNombre + " (folio " + t.getFolio()
                    + ", " + descripcionEstado + "). ¿Quieres retomarlo?";
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("tramiteExistente", Boolean.TRUE);
        r.put("tramiteId", t.getId().toString());
        r.put("folio", t.getFolio());
        r.put("estado", t.getEstado().name());
        r.put("estadoEtiqueta", etiquetaEstado(t.getEstado(), clinicaNombre));
        r.put("estudio", estudioNombre);
        r.put("precio", t.getPrecioCotizado() == null ? null : t.getPrecioCotizado().toPlainString());
        r.put("clinica", clinicaNombre);
        r.put("fechaCita", t.getFechaCita() == null ? null : t.getFechaCita().toString());
        r.put("faltantes", String.join(",", faltan));
        r.put("yaAgendada", yaAgendada);
        r.put("mensaje", mensaje);
        return r;
    }

    /**
     * Si ya existe un trámite activo de ese estudio para el paciente,
     * no se crea otro: 409 con el folio existente y lo que le falta
     * (clínica / fecha / hora), en JSON, para que el cliente ofrezca
     * retomarlo en vez de duplicarlo. El front lo lee con
     * parsearTramiteExistente (api.ts) y el bot lo pre-chequea con
     * buscarActivoPorEstudio antes de cotizar.
     */
    private void exigirSinDuplicadoActivo(UUID pacienteId, Long estudioId, UUID exceptoTramiteId) {
        Optional<Tramite> existente = buscarActivoPorEstudio(pacienteId, estudioId, exceptoTramiteId);
        if (existente.isEmpty()) return;
        // El cuerpo es el resumen de retomar (folio, estado, estudio,
        // costo, clínica, fecha/hora, faltantes y mensaje según el
        // estado). Llega al front porque server.error.include-message
        // está en always: sin eso, Spring omite el "message" del JSON
        // de error y el front solo veía "Conflict" (bug 2026-11-24).
        throw new ResponseStatusException(HttpStatus.CONFLICT, jsonObjeto(resumenRetomar(existente.get())));
    }

    /**
     * Crea un trámite resolviendo/creando al paciente por CURP (flujo
     * invitado de la Web: POST /api/tramites con `curp` y sin
     * `pacienteId`). Si la CURP ya existe, se usa ese paciente (se
     * ignoran nombre/teléfono del request); si no existe, se crea el
     * paciente con el nombre dado y la fecha de nacimiento que codifica
     * la CURP (el teléfono es opcional). La CURP se valida (400 si no
     * es válida) y el nombre es obligatorio solo cuando hay que crear
     * al paciente.
     */
    public Tramite crearTramitePorCurp(String curp, String nombre, String telefono,
                                       Long estudioId, String canalOrigen) {
        String curpNormalizada = CurpUtil.normalizar(curp);
        CurpUtil.validar(curpNormalizada).ifPresent(motivo -> {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, motivo);
        });
        Paciente paciente = pacientes.findByCurp(curpNormalizada).orElseGet(() -> {
            if (nombre == null || nombre.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El nombre es obligatorio para crear al paciente por CURP");
            }
            String tel = null;
            if (telefono != null && !telefono.isBlank()) {
                tel = TelefonoUtil.normalizar(telefono);
                if (tel != null && tel.isBlank()) {
                    tel = null;
                }
            }
            Paciente nuevo = new Paciente(nombre.trim(), tel, null, CurpUtil.fechaDesdeCurp(curpNormalizada));
            nuevo.setCurp(curpNormalizada);
            return pacientes.save(nuevo);
        });
        return crearTramite(paciente.getId(), estudioId, canalOrigen);
    }

    /**
     * Canal real de la acción: el que se pasa, o el canal de origen del
     * trámite si no viene. Los eventos del Journey llevan el canal
     * donde OCURRIÓ cada paso, no el canal donde se inició el trámite
     * (bug 2026-11-24: un trámite iniciado en WhatsApp y terminado en
     * Web mostraba WHATSAPP en todos los pasos).
     */
    private static String canalReal(String canal, Tramite tramite) {
        return canal == null || canal.isBlank() ? tramite.getCanalOrigen() : canal;
    }

    public Tramite cotizar(UUID tramiteId, Long estudioId) {
        return cotizar(tramiteId, estudioId, null);
    }

    public Tramite cotizar(UUID tramiteId, Long estudioId, String canal) {
        Tramite tramite = buscar(tramiteId);
        exigirEstado(tramite, EstadoTramite.INICIADO, EstadoTramite.DATOS_PENDIENTES);
        Estudio estudio = estudios.findById(estudioId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Estudio no encontrado: " + estudioId));
        // El trámite que se cotiza queda fuera de la búsqueda: el
        // flujo Web crea el trámite con el estudio y luego lo cotiza
        // con el mismo estudio. Otro trámite activo del mismo estudio
        // sí es duplicado: 409 con el folio existente para retomarlo.
        exigirSinDuplicadoActivo(tramite.getPaciente().getId(), estudioId, tramiteId);
        tramite.setEstudioId(estudio.getId());
        tramite.setPrecioCotizado(estudio.getPrecio());
        tramite.setEstado(EstadoTramite.COTIZADO);
        tramite = tramites.save(tramite);
        eventos.registrar(tramite.getId(), TipoEvento.COTIZACION_CREADA, canalReal(canal, tramite), "SISTEMA",
                json(Map.of("estudioId", String.valueOf(estudio.getId()),
                        "estudio", estudio.getNombre(),
                        "precio", estudio.getPrecio().toPlainString())));
        return tramite;
    }

    public Tramite seleccionarClinica(UUID tramiteId, Long clinicaId) {
        return seleccionarClinica(tramiteId, clinicaId, null);
    }

    public Tramite seleccionarClinica(UUID tramiteId, Long clinicaId, String canal) {
        Tramite tramite = buscar(tramiteId);
        exigirEstado(tramite, EstadoTramite.COTIZADO);
        Clinica clinica = clinicas.findById(clinicaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Clinica no encontrada: " + clinicaId));
        tramite.setClinicaId(clinica.getId());
        tramite.setEstado(EstadoTramite.CLINICA_SELECCIONADA);
        tramite = tramites.save(tramite);
        eventos.registrar(tramite.getId(), TipoEvento.CLINICA_SELECCIONADA, canalReal(canal, tramite), "PACIENTE",
                json(Map.of("clinicaId", String.valueOf(clinica.getId()), "clinica", clinica.getNombre())));
        return tramite;
    }

    /**
     * Vuelve un tramite de CLINICA_SELECCIONADA a COTIZADO para cambiar
     * de clinica (lo pide el bot de WhatsApp con "cambiar clinica" /
     * "otra clinica"). Se limpia la clinica elegida y el tramite queda
     * listo para elegir otra, sin crear un folio nuevo.
     */
    public Tramite volverACotizado(UUID tramiteId) {
        return volverACotizado(tramiteId, null);
    }

    public Tramite volverACotizado(UUID tramiteId, String canal) {
        Tramite tramite = buscar(tramiteId);
        exigirEstado(tramite, EstadoTramite.CLINICA_SELECCIONADA);
        tramite.setClinicaId(null);
        tramite.setEstado(EstadoTramite.COTIZADO);
        tramite = tramites.save(tramite);
        eventos.registrar(tramite.getId(), TipoEvento.CLINICA_SELECCIONADA, canalReal(canal, tramite), "PACIENTE",
                json(Map.of("accion", "cambiar_clinica")));
        return tramite;
    }

    /**
     * Parrilla de horarios: slots de 30 min de 07:00 a 17:00 (el último
     * slot es 16:30–17:00). Una cita CONFIRMADA ocupa el intervalo
     * [fechaHora, fechaHora + duracionMin del estudio de su trámite);
     * un slot está ocupado si se solapa con alguna cita CONFIRMADA de
     * esa clínica ese día. Duraciones reales del catálogo:
     * Ultrasonido 30, Perfil tiroideo 15, Radiografía 20, Análisis 10,
     * Electrocardiograma 20, Mastografía 30.
     */
    public static final LocalTime APERTURA = LocalTime.of(7, 0);
    public static final LocalTime CIERRE = LocalTime.of(17, 0);
    public static final int DURACION_SLOT_MIN = 30;
    public static final int DURACION_DEFAULT_MIN = 30;

    /** Un slot de la parrilla: hora de inicio ("HH:mm") y si está ocupado. */
    public record ParrillaSlot(String hora, boolean ocupado) {
    }

    private static final DateTimeFormatter FORMATO_HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter FORMATO_FECHA_LATINA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Duración en minutos de un estudio (30 si no existe o no tiene duración). */
    @Transactional(readOnly = true)
    public int duracionEstudio(Long estudioId) {
        if (estudioId == null) {
            return DURACION_DEFAULT_MIN;
        }
        return estudios.findById(estudioId)
                .map(Estudio::getDuracionMin)
                .filter(d -> d != null && d > 0)
                .orElse(DURACION_DEFAULT_MIN);
    }

    /** ¿Existe la clínica? (para validar la parrilla antes de calcularla). */
    @Transactional(readOnly = true)
    public boolean existeClinica(Long clinicaId) {
        return clinicaId != null && clinicas.existsById(clinicaId);
    }

    /** ¿Existe el estudio? (para validar la parrilla antes de calcularla). */
    @Transactional(readOnly = true)
    public boolean existeEstudio(Long estudioId) {
        return estudioId != null && estudios.existsById(estudioId);
    }

    /** Duración de una cita ya guardada: la del estudio de su trámite (30 si no se sabe). */
    private int duracionDeCita(Cita cita) {
        Long estudioId = cita.getTramite() == null ? null : cita.getTramite().getEstudioId();
        return duracionEstudio(estudioId);
    }

    /** ¿Se cruzan [aInicio, aInicio+aMin) y [bInicio, bInicio+bMin)? */
    private static boolean solapan(LocalDateTime aInicio, int aMin, LocalDateTime bInicio, int bMin) {
        return aInicio.isBefore(bInicio.plusMinutes(bMin)) && bInicio.isBefore(aInicio.plusMinutes(aMin));
    }

    /** Citas CONFIRMADAS de una clínica en un día (excluye la del trámite dado, si se pasa). */
    @Transactional(readOnly = true)
    public List<Cita> citasConfirmadasDelDia(Long clinicaId, LocalDate fecha, UUID exceptoTramiteId) {
        LocalDateTime desde = fecha.atStartOfDay();
        LocalDateTime hasta = fecha.plusDays(1).atStartOfDay();
        return citas.findByClinicaIdAndFechaHoraBetween(clinicaId, desde, hasta).stream()
                .filter(c -> "CONFIRMADA".equals(c.getEstado()))
                .filter(c -> exceptoTramiteId == null
                        || c.getTramite() == null || !c.getTramite().getId().equals(exceptoTramiteId))
                .toList();
    }

    /**
     * Parrilla del día: los 20 slots (07:00, 07:30, …, 16:30) con su
     * estado. `estudioId` solo fija la duración de la cita nueva para
     * el solape futuro (la parrilla en sí marca ocupado por las citas
     * que ya existen); si no viene, 30 min.
     */
    @Transactional(readOnly = true)
    public List<ParrillaSlot> parrilla(Long clinicaId, LocalDate fecha, Long estudioId, UUID exceptoTramiteId) {
        List<Cita> confirmadas = citasConfirmadasDelDia(clinicaId, fecha, exceptoTramiteId);
        // Un hueco que ya pasó hoy no se puede reservar (confirmar lo
        // rechazaría con 400): se marca ocupado para no ofrecerlo.
        LocalDateTime ahora = LocalDateTime.now();
        boolean esHoy = fecha.equals(ahora.toLocalDate());
        List<ParrillaSlot> slots = new ArrayList<>();
        for (LocalTime hora = APERTURA; hora.isBefore(CIERRE); hora = hora.plusMinutes(DURACION_SLOT_MIN)) {
            LocalDateTime inicioSlot = fecha.atTime(hora);
            boolean ocupado = (esHoy && inicioSlot.isBefore(ahora))
                    || confirmadas.stream().anyMatch(c ->
                    solapan(inicioSlot, DURACION_SLOT_MIN, c.getFechaHora(), duracionDeCita(c)));
            slots.add(new ParrillaSlot(hora.format(FORMATO_HORA), ocupado));
        }
        return slots;
    }

    /** Horas libres ("HH:mm") de la parrilla de un día, en orden. */
    @Transactional(readOnly = true)
    public List<String> horasLibresDelDia(Long clinicaId, LocalDate fecha, Long estudioId, UUID exceptoTramiteId) {
        return parrilla(clinicaId, fecha, estudioId, exceptoTramiteId).stream()
                .filter(s -> !s.ocupado())
                .map(ParrillaSlot::hora)
                .toList();
    }

    /** Horas libres más cercanas a una hora pedida (máx. 3, en orden cronológico). */
    @Transactional(readOnly = true)
    public List<String> horasLibresCercanas(Long clinicaId, LocalDate fecha, LocalTime pedida,
                                            Long estudioId, UUID exceptoTramiteId) {
        List<String> libres = horasLibresDelDia(clinicaId, fecha, estudioId, exceptoTramiteId);
        return libres.stream()
                .sorted(Comparator.comparingLong((String h) ->
                                Math.abs(java.time.Duration.between(pedida, LocalTime.parse(h, FORMATO_HORA)).toMinutes()))
                        .thenComparing(h -> LocalTime.parse(h, FORMATO_HORA)))
                .limit(3)
                .sorted(Comparator.comparing(h -> LocalTime.parse(h, FORMATO_HORA)))
                .toList();
    }

    public Tramite confirmar(UUID tramiteId, LocalDateTime fechaCita) {
        return confirmar(tramiteId, fechaCita, null);
    }

    public Tramite confirmar(UUID tramiteId, LocalDateTime fechaCita, String canal) {
        Tramite tramite = buscar(tramiteId);
        exigirEstado(tramite, EstadoTramite.CLINICA_SELECCIONADA);
        if (fechaCita == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fechaCita es obligatoria");
        }
        if (fechaCita.isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La fecha y hora de la cita ya pasaron: "
                            + fechaCita.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")));
        }
        // (a) Parrilla: 07:00–16:30, minutos múltiplo de 30.
        LocalTime hora = fechaCita.toLocalTime();
        if (hora.isBefore(APERTURA) || hora.isAfter(LocalTime.of(16, 30))
                || hora.getMinute() % DURACION_SLOT_MIN != 0 || hora.getSecond() != 0 || hora.getNano() != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La hora debe estar dentro de la parrilla 07:00–16:30, en múltiplos de 30 minutos "
                            + "(07:00, 07:30, …, 16:30). Hora pedida: " + hora.format(FORMATO_HORA) + ".");
        }
        if (tramite.getClinicaId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El tramite no tiene clinica seleccionada");
        }
        Clinica clinica = clinicas.findById(tramite.getClinicaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Clinica no encontrada"));
        // (c) Sin solapamiento: la cita nueva ocupa [fechaCita,
        // fechaCita + duracionMin del estudio) y no puede cruzarse con
        // otra cita CONFIRMADA de esa clínica. La propia cita del
        // tramite (si se reconfirma tras cambiar de clinica) no cuenta.
        int duracionNueva = duracionEstudio(tramite.getEstudioId());
        if (huecoOcupado(tramite.getClinicaId(), fechaCita, duracionNueva, tramiteId)) {
            List<String> cercanas = horasLibresCercanas(tramite.getClinicaId(), fechaCita.toLocalDate(),
                    fechaCita.toLocalTime(), tramite.getEstudioId(), tramiteId);
            String detalle = cercanas.isEmpty()
                    ? "No quedan horas libres ese día en " + clinica.getNombre() + "."
                    : "Horas libres cercanas: " + String.join(", ", cercanas) + ".";
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La hora " + fechaCita.format(FORMATO_HORA) + " está ocupada en " + clinica.getNombre()
                            + " el " + fechaCita.format(FORMATO_FECHA_LATINA) + ". " + detalle);
        }
        tramite.setFechaCita(fechaCita);
        tramite.setEstado(EstadoTramite.CONFIRMADO);
        tramite = tramites.save(tramite);
        // La cita es @OneToOne con tramite_id unico: si el tramite ya
        // tenia una cita (reconfirmacion tras cambiar de clinica), se
        // actualiza en vez de crear otra fila.
        Optional<Cita> existente = citas.findByTramiteId(tramiteId);
        if (existente.isPresent()) {
            Cita cita = existente.get();
            cita.setFechaHora(fechaCita);
            cita.setClinica(clinica);
            cita.setEstado("CONFIRMADA");
            citas.save(cita);
        } else {
            citas.save(new Cita(tramite, fechaCita, clinica, "CONFIRMADA"));
        }
        eventos.registrar(tramite.getId(), TipoEvento.CITA_CONFIRMADA, canalReal(canal, tramite), "PACIENTE",
                json(Map.of("fechaCita", fechaCita.toString(), "clinica", clinica.getNombre())));
        return tramite;
    }

    /**
     * ¿El hueco (clinica, fecha, hora) esta ocupado por otra cita
     * CONFIRMADA? `exceptoTramiteId` (opcional) excluye la cita propia
     * del tramite que esta reconfirmando. Ahora es por solape de
     * intervalos (no igualdad exacta): la cita nueva se toma de 30 min
     * y una cita existente ocupa [fechaHora, fechaHora + duracionMin
     * de su estudio). Ver la sobrecarga con duracionMinNueva para la
     * duración real del estudio.
     */
    @Transactional(readOnly = true)
    public boolean huecoOcupado(Long clinicaId, LocalDateTime fechaHora, UUID exceptoTramiteId) {
        return huecoOcupado(clinicaId, fechaHora, DURACION_DEFAULT_MIN, exceptoTramiteId);
    }

    /** Como huecoOcupado, con la duración real (min) de la cita nueva. */
    @Transactional(readOnly = true)
    public boolean huecoOcupado(Long clinicaId, LocalDateTime fechaHora, int duracionMinNueva, UUID exceptoTramiteId) {
        return citasConfirmadasDelDia(clinicaId, fechaHora.toLocalDate(), exceptoTramiteId).stream()
                .anyMatch(c -> solapan(fechaHora, duracionMinNueva, c.getFechaHora(), duracionDeCita(c)));
    }

    /**
     * Horas ocupadas ("HH:mm") de una clinica en un dia, por citas CONFIRMADAS.
     * CAMBIO 2026-11-24: ya no son las horas exactas de inicio de las
     * citas, sino los inicios de slot de la parrilla (07:00–16:30, cada
     * 30 min) que se solapan con alguna cita: una cita de 10:15 de
     * 20 min ocupa los slots 10:00 y 10:30. El contrato (lista de
     * "HH:mm" ordenada) no cambia.
     */
    @Transactional(readOnly = true)
    public List<String> horasOcupadasDelDia(Long clinicaId, java.time.LocalDate fecha) {
        return parrilla(clinicaId, fecha, null, null).stream()
                .filter(ParrillaSlot::ocupado)
                .map(ParrillaSlot::hora)
                .toList();
    }

    /**
     * Completa un dato pendiente (ej. CURP, orden medica). Los pendientes
     * viven como JSON en datosPendientes; al vaciarse, un tramite en
     * DATOS_PENDIENTES vuelve a INICIADO para poder cotizar.
     */
    public Tramite completarDatoPendiente(UUID tramiteId, String clave, String valor) {
        return completarDatoPendiente(tramiteId, clave, valor, null);
    }

    public Tramite completarDatoPendiente(UUID tramiteId, String clave, String valor, String canal) {
        Tramite tramite = buscar(tramiteId);
        exigirEstado(tramite, EstadoTramite.INICIADO, EstadoTramite.DATOS_PENDIENTES, EstadoTramite.COTIZADO);
        if (clave == null || clave.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clave es obligatoria");
        }
        Map<String, String> pendientes = leerPendientes(tramite.getDatosPendientes());
        pendientes.remove(clave);
        tramite.setDatosPendientes(pendientes.isEmpty() ? null : json(pendientes));
        if (tramite.getEstado() == EstadoTramite.DATOS_PENDIENTES && pendientes.isEmpty()) {
            tramite.setEstado(EstadoTramite.INICIADO);
        }
        tramite = tramites.save(tramite);
        eventos.registrar(tramite.getId(), TipoEvento.DATO_PENDIENTE_COMPLETADO, canalReal(canal, tramite), "PACIENTE",
                json(Map.of("clave", clave, "pendientesRestantes", String.valueOf(pendientes.size()))));
        return tramite;
    }

    /** Marca datos como pendientes y deja el tramite en DATOS_PENDIENTES. */
    public Tramite marcarDatosPendientes(UUID tramiteId, Map<String, String> pendientes) {
        Tramite tramite = buscar(tramiteId);
        exigirEstado(tramite, EstadoTramite.INICIADO, EstadoTramite.DATOS_PENDIENTES);
        tramite.setDatosPendientes(json(pendientes));
        tramite.setEstado(EstadoTramite.DATOS_PENDIENTES);
        tramite = tramites.save(tramite);
        return tramite;
    }

    /** Recupera un tramite por otro canal: no cambia el estado, solo deja constancia y devuelve el contexto. */
    public Tramite recuperar(UUID tramiteId, String canal) {
        Tramite tramite = buscar(tramiteId);
        if (ESTADOS_TERMINALES.contains(tramite.getEstado())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El tramite ya esta cerrado (" + tramite.getEstado() + "), no se puede recuperar");
        }
        String canalReal = canal == null || canal.isBlank() ? tramite.getCanalOrigen() : canal;
        eventos.registrar(tramite.getId(), TipoEvento.TRAMITE_RECUPERADO, canalReal, "SISTEMA",
                json(Map.of("estadoActual", tramite.getEstado().name(), "folio", tramite.getFolio())));
        eventos.registrar(tramite.getId(), TipoEvento.CANAL_CAMBIADO, canalReal, "SISTEMA",
                json(Map.of("canalAnterior", tramite.getCanalOrigen(), "canalNuevo", canalReal)));
        return tramite;
    }

    public Tramite concluirAtencion(UUID tramiteId) {
        Tramite tramite = buscar(tramiteId);
        exigirEstado(tramite, EstadoTramite.CONFIRMADO);
        tramite.setEstado(EstadoTramite.ATENDIDO);
        tramite = tramites.save(tramite);
        citas.findByTramiteId(tramiteId).ifPresent(cita -> {
            cita.setEstado("ATENDIDA");
            citas.save(cita);
        });
        eventos.registrar(tramite.getId(), TipoEvento.ATENCION_CONCLUIDA, "CLINICA", "CLINICA",
                json(Map.of("folio", tramite.getFolio())));
        return tramite;
    }

    /**
     * Check-in del doctor: el paciente llego a la clinica. Solo deja
     * constancia en el log (evento CHECK_IN); el estado sigue
     * CONFIRMADO hasta que se marque atendido / no presentado /
     * no realizado. Se puede registrar una sola vez por tramite.
     */
    public Tramite registrarCheckIn(UUID tramiteId) {
        Tramite tramite = buscar(tramiteId);
        exigirEstado(tramite, EstadoTramite.CONFIRMADO);
        if (eventos.tieneEvento(tramiteId, TipoEvento.CHECK_IN)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El check-in del folio " + tramite.getFolio() + " ya esta registrado");
        }
        eventos.registrar(tramite.getId(), TipoEvento.CHECK_IN, "CLINICA", "DOCTOR",
                json(Map.of("folio", tramite.getFolio())));
        return tramite;
    }

    /** El paciente no se presento a su cita confirmada: estado terminal, sale de los activos. */
    public Tramite marcarNoPresentado(UUID tramiteId) {
        Tramite tramite = buscar(tramiteId);
        exigirEstado(tramite, EstadoTramite.CONFIRMADO);
        tramite.setEstado(EstadoTramite.NO_PRESENTADO);
        tramite = tramites.save(tramite);
        citas.findByTramiteId(tramiteId).ifPresent(cita -> {
            cita.setEstado("NO_PRESENTADO");
            citas.save(cita);
        });
        eventos.registrar(tramite.getId(), TipoEvento.NO_PRESENTADO, "CLINICA", "DOCTOR",
                json(Map.of("folio", tramite.getFolio())));
        return tramite;
    }

    /**
     * El estudio no se pudo realizar: el doctor apunta el motivo
     * (texto libre, obligatorio). Queda en el log de eventos y en el
     * journey del folio; el estado es terminal, sale de los activos.
     */
    public Tramite marcarNoRealizado(UUID tramiteId, String motivo) {
        Tramite tramite = buscar(tramiteId);
        exigirEstado(tramite, EstadoTramite.CONFIRMADO);
        if (motivo == null || motivo.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El motivo es obligatorio: apunta por que no se pudo realizar el estudio");
        }
        tramite.setEstado(EstadoTramite.NO_REALIZADO);
        tramite = tramites.save(tramite);
        citas.findByTramiteId(tramiteId).ifPresent(cita -> {
            cita.setEstado("NO_REALIZADO");
            citas.save(cita);
        });
        eventos.registrar(tramite.getId(), TipoEvento.NO_REALIZADO, "CLINICA", "DOCTOR",
                json(Map.of("folio", tramite.getFolio(), "motivo", motivo.trim())));
        return tramite;
    }

    /**
     * Solo tramites no terminales y no eliminados se pueden editar,
     * cancelar o eliminar (409 si ya esta cerrado o eliminado).
     */
    private void exigirEditable(Tramite tramite) {
        if (tramite.isEliminado()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El tramite " + tramite.getFolio() + " esta eliminado, no se puede modificar");
        }
        if (ESTADOS_TERMINALES.contains(tramite.getEstado())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El tramite " + tramite.getFolio() + " ya esta cerrado ("
                            + etiquetaEstado(tramite.getEstado(), null) + "), no se puede modificar");
        }
    }

    private String nombreEstudioDe(Long estudioId) {
        if (estudioId == null) return "sin estudio";
        return estudios.findById(estudioId).map(Estudio::getNombre).orElse("ese estudio");
    }

    private String nombreClinicaDe(Long clinicaId) {
        if (clinicaId == null) return "sin clinica";
        return clinicas.findById(clinicaId).map(Clinica::getNombre).orElse("esa clinica");
    }

    /** Estado que corresponde a los datos presentes: estudio+precio -> COTIZADO; +clinica -> CLINICA_SELECCIONADA; +fecha -> CONFIRMADO; sin estudio se conserva INICIADO/DATOS_PENDIENTES. */
    private static EstadoTramite recomputarEstado(Tramite tramite) {
        if (tramite.getEstudioId() == null || tramite.getPrecioCotizado() == null) {
            return tramite.getEstado() == EstadoTramite.DATOS_PENDIENTES
                    ? EstadoTramite.DATOS_PENDIENTES : EstadoTramite.INICIADO;
        }
        if (tramite.getFechaCita() != null && tramite.getClinicaId() != null) return EstadoTramite.CONFIRMADO;
        if (tramite.getClinicaId() != null) return EstadoTramite.CLINICA_SELECCIONADA;
        return EstadoTramite.COTIZADO;
    }

    /** Fecha/hora de cita valida para editar: futura, dentro de la parrilla (07:00-16:30, multiplos de 30) y con el hueco libre (excepto el propio tramite). Mismas reglas que confirmar(). */
    private void validarFechaCita(LocalDateTime fechaCita, Long clinicaId, Long estudioId, UUID tramiteId) {
        if (fechaCita.isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La fecha y hora de la cita ya pasaron: "
                            + fechaCita.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")));
        }
        LocalTime hora = fechaCita.toLocalTime();
        if (hora.isBefore(APERTURA) || hora.isAfter(LocalTime.of(16, 30))
                || hora.getMinute() % DURACION_SLOT_MIN != 0 || hora.getSecond() != 0 || hora.getNano() != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La hora debe estar dentro de la parrilla 07:00–16:30, en múltiplos de 30 minutos "
                            + "(07:00, 07:30, …, 16:30). Hora pedida: " + hora.format(FORMATO_HORA) + ".");
        }
        Clinica clinica = clinicas.findById(clinicaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Clinica no encontrada"));
        int duracionNueva = duracionEstudio(estudioId);
        if (huecoOcupado(clinicaId, fechaCita, duracionNueva, tramiteId)) {
            List<String> cercanas = horasLibresCercanas(clinicaId, fechaCita.toLocalDate(),
                    fechaCita.toLocalTime(), estudioId, tramiteId);
            String detalle = cercanas.isEmpty()
                    ? "No quedan horas libres ese día en " + clinica.getNombre() + "."
                    : "Horas libres cercanas: " + String.join(", ", cercanas) + ".";
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La hora " + fechaCita.format(FORMATO_HORA) + " está ocupada en " + clinica.getNombre()
                            + " el " + fechaCita.format(FORMATO_FECHA_LATINA) + ". " + detalle);
        }
    }

    /**
     * Edita la reservacion (asesor): estudio, clinica y/o fecha y hora.
     * Los parametros null significan "sin cambio" (el front manda los
     * valores actuales). La NOTA es obligatoria (400 si viene vacia,
     * en backend y en front): queda en el log con los cambios
     * antes/despues. Solo tramites no terminales y no eliminados
     * (409 si no). Validaciones: estudio/clinica existen; si cambia el
     * estudio se re-cotiza el precio y se chequea duplicado activo de
     * ese estudio (excepto si mismo); si hay fecha nueva debe ser
     * futura y con el hueco libre (excepto si mismo). El estado se
     * recomputa desde los datos presentes y la fila `citas` se
     * sincroniza (crear/actualizar si CONFIRMADO; si deja de estarlo,
     * su cita pasa a CANCELADA). Evento tramite.editado con
     * {accion:"edicion", nota, cambios} canal CALL_CENTER actor ASESOR.
     */
    public Tramite editarTramite(UUID tramiteId, Long estudioId, Long clinicaId,
                                 LocalDateTime fechaCita, String nota, String canal) {
        Tramite tramite = buscar(tramiteId);
        exigirEditable(tramite);
        if (nota == null || nota.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La nota es obligatoria: explica que cambiaste y por que");
        }
        List<String> cambios = new ArrayList<>();
        boolean cambioEstudio = false;
        if (estudioId != null && !estudioId.equals(tramite.getEstudioId())) {
            Estudio estudio = estudios.findById(estudioId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Estudio no encontrado: " + estudioId));
            exigirSinDuplicadoActivo(tramite.getPaciente().getId(), estudioId, tramiteId);
            cambios.add("estudio: " + nombreEstudioDe(tramite.getEstudioId()) + " → " + estudio.getNombre()
                    + " ($" + estudio.getPrecio().toPlainString() + ")");
            tramite.setEstudioId(estudio.getId());
            tramite.setPrecioCotizado(estudio.getPrecio());
            cambioEstudio = true;
        }
        if (clinicaId != null && !clinicaId.equals(tramite.getClinicaId())) {
            Clinica clinica = clinicas.findById(clinicaId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Clinica no encontrada: " + clinicaId));
            cambios.add("clínica: " + nombreClinicaDe(tramite.getClinicaId()) + " → " + clinica.getNombre());
            tramite.setClinicaId(clinica.getId());
        }
        if (fechaCita != null && !fechaCita.equals(tramite.getFechaCita())) {
            if (tramite.getClinicaId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Elige primero la clinica para poder poner fecha y hora a la cita");
            }
            validarFechaCita(fechaCita, tramite.getClinicaId(), tramite.getEstudioId(), tramiteId);
            String antes = tramite.getFechaCita() == null ? "sin fecha"
                    : tramite.getFechaCita().format(FORMATO_LATINO_HORA);
            cambios.add("fecha: " + antes + " → " + fechaCita.format(FORMATO_LATINO_HORA));
            tramite.setFechaCita(fechaCita);
        }
        if (cambios.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No cambiaste nada: elige otro estudio, otra clinica u otra fecha y hora");
        }
        // Si solo cambio el estudio (la fecha se conserva), el nuevo
        // estudio puede ser mas largo y solaparse con la cita siguiente:
        // se re-chequea SOLO el solape de la fecha que ya tenia (sin
        // re-validar pasado/parrilla: esa cita ya estaba confirmada).
        if (cambioEstudio && tramite.getFechaCita() != null && tramite.getClinicaId() != null
                && huecoOcupado(tramite.getClinicaId(), tramite.getFechaCita(),
                        duracionEstudio(tramite.getEstudioId()), tramiteId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Con el estudio nuevo la cita del "
                            + tramite.getFechaCita().format(FORMATO_LATINO_HORA)
                            + " se solapa con otra cita de esa clinica. Elige otra fecha y hora.");
        }
        tramite.setEstado(recomputarEstado(tramite));
        tramite = tramites.save(tramite);
        // Sincronizar la fila `citas` (mismo patron que confirmar /
        // marcarNoRealizado): CONFIRMADO -> cita CONFIRMADA (se crea
        // o se actualiza); si ya no esta confirmado, su cita pasa a
        // CANCELADA para liberar el hueco.
        if (tramite.getEstado() == EstadoTramite.CONFIRMADO) {
            Clinica clinica = clinicas.findById(tramite.getClinicaId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Clinica no encontrada"));
            LocalDateTime fechaFinal = tramite.getFechaCita();
            Optional<Cita> existente = citas.findByTramiteId(tramiteId);
            if (existente.isPresent()) {
                Cita cita = existente.get();
                cita.setFechaHora(fechaFinal);
                cita.setClinica(clinica);
                cita.setEstado("CONFIRMADA");
                citas.save(cita);
            } else {
                citas.save(new Cita(tramite, fechaFinal, clinica, "CONFIRMADA"));
            }
        } else {
            citas.findByTramiteId(tramiteId).ifPresent(cita -> {
                if ("CONFIRMADA".equals(cita.getEstado())) {
                    cita.setEstado("CANCELADA");
                    citas.save(cita);
                }
            });
        }
        eventos.registrar(tramite.getId(), TipoEvento.TRAMITE_EDITADO, canalReal(canal, tramite), "ASESOR",
                json(Map.of("accion", "edicion", "nota", nota.trim(),
                        "cambios", String.join("; ", cambios))));
        return tramite;
    }

    /**
     * Cancela un tramite (asesor): motivo obligatorio (400 si vacio).
     * Permitido desde cualquier estado no terminal y no eliminado
     * (409 si no). El estado pasa a CANCELADO (terminal: sale de los
     * activos y no bloquea un tramite nuevo del mismo estudio) y la
     * fila `citas` pasa a "CANCELADO": el hueco queda libre porque la
     * parrilla y huecoOcupado solo cuentan citas CONFIRMADAS.
     * Evento tramite.cancelado {accion:"cancelacion", motivo} canal
     * CALL_CENTER actor ASESOR. El historial y el Journey conservan
     * el folio con su motivo.
     */
    public Tramite cancelarTramite(UUID tramiteId, String motivo, String canal) {
        Tramite tramite = buscar(tramiteId);
        exigirEditable(tramite);
        if (motivo == null || motivo.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El motivo es obligatorio: apunta por que se cancela el tramite");
        }
        tramite.setEstado(EstadoTramite.CANCELADO);
        tramite = tramites.save(tramite);
        citas.findByTramiteId(tramiteId).ifPresent(cita -> {
            cita.setEstado("CANCELADO");
            citas.save(cita);
        });
        eventos.registrar(tramite.getId(), TipoEvento.TRAMITE_CANCELADO, canalReal(canal, tramite), "ASESOR",
                json(Map.of("accion", "cancelacion", "motivo", motivo.trim())));
        return tramite;
    }

    /**
     * Elimina un tramite (asesor): BORRADO SUAVE, nunca en duro.
     * Motivo obligatorio (400 si vacio). Pone eliminado=true,
     * eliminadoMotivo=motivo y estado CANCELADO, y la cita en
     * "CANCELADO" (mismo efecto de liberar el hueco y salir de
     * activos que cancelar). El tramite y TODOS sus eventos se
     * conservan: el folio sale de los listados pero sigue consultable
     * por folio/id (solo lectura, badge "Eliminado") y su historial
     * muestra la eliminacion con su motivo. Evento tramite.eliminado
     * {accion:"eliminacion", motivo} canal CALL_CENTER actor ASESOR.
     * No confundir con eliminarTramiteVacio (borrado en duro solo de
     * folios vacios que nunca debieron existir).
     */
    public Tramite eliminarTramite(UUID tramiteId, String motivo, String canal) {
        Tramite tramite = buscar(tramiteId);
        exigirEditable(tramite);
        if (motivo == null || motivo.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El motivo es obligatorio: apunta por que se elimina el tramite");
        }
        tramite.setEliminado(true);
        tramite.setEliminadoMotivo(motivo.trim());
        tramite.setEstado(EstadoTramite.CANCELADO);
        tramite = tramites.save(tramite);
        citas.findByTramiteId(tramiteId).ifPresent(cita -> {
            cita.setEstado("CANCELADO");
            citas.save(cita);
        });
        eventos.registrar(tramite.getId(), TipoEvento.TRAMITE_ELIMINADO, canalReal(canal, tramite), "ASESOR",
                json(Map.of("accion", "eliminacion", "motivo", motivo.trim())));
        return tramite;
    }

    @Transactional(readOnly = true)
    public Tramite buscar(UUID tramiteId) {
        return tramites.findById(tramiteId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tramite no encontrado: " + tramiteId));
    }

    /** Busca un tramite por su folio legible (SD-2026-XXXX). 404 si no existe.
     * El folio se normaliza (espacios y minusculas) para que "sd-2026-0012" funcione igual. */
    @Transactional(readOnly = true)
    public Tramite buscarPorFolio(String folio) {
        String normalizado = folio == null ? "" : folio.trim().toUpperCase();
        return tramites.findByFolio(normalizado)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tramite no encontrado con folio " + normalizado));
    }

    /** El tramite por folio como mapa plano (mismo formato que el journey):
     * el endpoint /tramites/folio/{folio} ya no devuelve la entidad JPA
     * con su paciente LAZY, sino datos resueltos. */
    @Transactional(readOnly = true)
    public Map<String, Object> buscarPorFolioComoMapa(String folio) {
        return tramiteComoMapa(buscarPorFolio(folio));
    }

    /** Todos los tramites en un estado dado, para la recepcion de clinica y tableros. Los eliminados (borrado suave) no salen: siguen consultables por folio/id. */
    @Transactional(readOnly = true)
    public List<Tramite> listarPorEstado(EstadoTramite estado) {
        return tramites.findByEstado(estado).stream()
                .filter(t -> !t.isEliminado())
                .toList();
    }

    /**
     * Citas para el tablero de Clínica como DTOs planos (no entidades JPA).
     * Se construyen dentro de la transacción, cargando explícitamente
     * paciente, estudio y clínica, así la serialización nunca depende
     * de un proxy LAZY. Si estado es null, devuelve todas las citas.
     */
    @Transactional(readOnly = true)
    public List<CitaClinicaDTO> listarCitasClinica(EstadoTramite estado) {
        List<Tramite> lista = estado == null ? tramites.findAll() : tramites.findByEstado(estado);
        // Borrado suave: los eliminados no salen del tablero de clinica.
        lista = lista.stream().filter(t -> !t.isEliminado()).toList();
        return lista.stream().map(t -> {
            Paciente paciente = t.getPaciente();
            String estudioNombre = t.getEstudioId() == null ? "Sin estudio"
                    : estudios.findById(t.getEstudioId()).map(Estudio::getNombre).orElse("Sin estudio");
            String clinicaNombre = null;
            String clinicaCiudad = null;
            if (t.getClinicaId() != null) {
                Clinica c = clinicas.findById(t.getClinicaId()).orElse(null);
                if (c != null) {
                    clinicaNombre = c.getNombre();
                    clinicaCiudad = c.getCiudad();
                }
            }
            return new CitaClinicaDTO(
                    t.getFolio(),
                    t.getId().toString(),
                    paciente == null ? "—" : paciente.getNombre(),
                    paciente == null || paciente.getTelefono() == null ? "—" : paciente.getTelefono(),
                    estudioNombre,
                    t.getPrecioCotizado(),
                    clinicaNombre,
                    clinicaCiudad,
                    t.getFechaCita(),
                    t.getEstado().name(),
                    eventos.tieneEvento(t.getId(), TipoEvento.CHECK_IN));
        }).toList();
    }

    /**
     * Citas confirmadas de un dia (por defecto hoy), para el tablero
     * del doctor: folio, paciente, estudio, clinica, fecha/hora y si
     * ya se registro el check-in. Solo CONFIRMADO.
     */
    @Transactional(readOnly = true)
    public List<CitaClinicaDTO> listarCitasDoctor(java.time.LocalDate fecha) {
        java.time.LocalDate dia = fecha == null ? java.time.LocalDate.now() : fecha;
        return listarCitasClinica(EstadoTramite.CONFIRMADO).stream()
                .filter(c -> c.fechaCita() != null && c.fechaCita().toLocalDate().equals(dia))
                .sorted(java.util.Comparator.comparing(CitaClinicaDTO::fechaCita))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Tramite> listarTodos() {
        // Borrado suave: los eliminados no salen del listado general.
        return tramites.findAll().stream().filter(t -> !t.isEliminado()).toList();
    }

    /** Estados terminales: el tramite ya se cerro y no vuelve a la lista de activos. CANCELADO entra aqui: un folio cancelado no bloquea un tramite nuevo del mismo estudio y sale de los activos. */
    public static final List<EstadoTramite> ESTADOS_TERMINALES =
            List.of(EstadoTramite.ATENDIDO, EstadoTramite.NO_PRESENTADO, EstadoTramite.NO_REALIZADO,
                    EstadoTramite.CANCELADO);

    /**
     * Tramites activos (no terminales) de un paciente, del mas reciente al mas viejo.
     * Un paciente puede tener varios folios activos a la vez, uno por estudio.
     */
    @Transactional(readOnly = true)
    public List<Tramite> listarActivosDe(UUID pacienteId) {
        return tramites.findByPacienteIdAndEstadoNotInOrderByCreatedAtDesc(pacienteId, ESTADOS_TERMINALES);
    }

    @Transactional(readOnly = true)
    public List<Tramite> listarPorTelefono(String telefono) {
        Paciente paciente = pacientes.findByTelefono(
                        mx.saluddigna.omnicanal.identidad.TelefonoUtil.normalizar(telefono))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Paciente no encontrado con telefono " + telefono));
        // Borrado suave: los eliminados no salen del listado.
        return tramites.findByPacienteIdOrderByCreatedAtDesc(paciente.getId()).stream()
                .filter(t -> !t.isEliminado())
                .toList();
    }

    /**
     * Trámites de un paciente como mapas planos (mismo formato que
     * buscarPorFolioComoMapa / journey: paciente, estudio y clínica ya
     * resueltos). Lo usa GET /api/pacientes/{id}/tramites. AQUI SI
     * salen los eliminados (con eliminado=true y su motivo): el asesor
     * los necesita para el historial del paciente; el front los filtra
     * de "Folios del paciente" y los muestra aparte como eliminados.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarDePacienteComoMapa(UUID pacienteId) {
        return tramites.findByPacienteIdOrderByCreatedAtDesc(pacienteId).stream()
                .map(this::tramiteComoMapa)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<Tramite> tramiteActivoDe(UUID pacienteId) {
        return listarActivosDe(pacienteId).stream().findFirst();
    }

    /**
     * Vista journey: tramite + paciente + estudio + clinica + timeline + siguiente paso.
     * Se devuelve TODO como datos planos (mapas y listas, no entidades JPA):
     * antes se devolvía el Tramite y la Cita como entidades, con sus
     * relaciones LAZY (paciente, tramite de la cita), y la serialización
     * Jackson del mapa fallaba con 500, así que el front mostraba "No se
     * encontró el trámite" para CUALQUIER folio. Mismo patrón que el DTO
     * de Clínica: los datos se arman dentro de la transacción.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> obtenerJourney(UUID tramiteId) {
        Tramite tramite = buscar(tramiteId);
        Map<String, Object> journey = new LinkedHashMap<>();
        journey.put("tramite", tramiteComoMapa(tramite));
        journey.put("paciente", pacienteComoMapa(tramite.getPaciente()));
        if (tramite.getEstudioId() != null) {
            estudios.findById(tramite.getEstudioId()).ifPresent(e -> journey.put("estudio", mapaDe(
                    "id", String.valueOf(e.getId()),
                    "nombre", e.getNombre(),
                    "precio", e.getPrecio(),
                    "descripcion", e.getDescripcion())));
        }
        if (tramite.getClinicaId() != null) {
            clinicas.findById(tramite.getClinicaId()).ifPresent(c -> journey.put("clinica", mapaDe(
                    "id", String.valueOf(c.getId()),
                    "nombre", c.getNombre(),
                    "ciudad", c.getCiudad(),
                    "direccion", c.getDireccion())));
        }
        citas.findByTramiteId(tramiteId).ifPresent(c -> journey.put("cita", mapaDe(
                "id", c.getId().toString(),
                "fechaHora", c.getFechaHora() == null ? null : c.getFechaHora().toString(),
                "clinica", c.getClinica() == null ? null : c.getClinica().getNombre(),
                "estado", c.getEstado())));
        journey.put("timeline", eventos.listarTimeline(tramiteId).stream().map(ev -> mapaDe(
                "id", ev.getId().toString(),
                "tramiteId", ev.getTramiteId().toString(),
                "tipo", ev.getTipo(),
                "canal", ev.getCanal(),
                "actor", ev.getActor(),
                "payload", ev.getPayload(),
                // Mensaje legible derivado del payload (nota/motivo):
                // sin esto la nota de una edicion o el motivo de una
                // cancelacion quedaban escondidos en un JSON que el
                // front no mostraba (Timeline solo leia ev.mensaje).
                "mensaje", mensajeDeEvento(ev.getTipo(), ev.getPayload()),
                "timestamp", ev.getTimestamp() == null ? null : ev.getTimestamp().toString()
        )).toList());
        journey.put("siguientePaso", siguientePaso(tramite.getEstado()));
        return journey;
    }

    /** Mapa plano de un tramite, con las mismas claves que espera el
        front (ver normalizeTramite en api.ts): nada de entidades JPA. */
    private Map<String, Object> tramiteComoMapa(Tramite t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId().toString());
        m.put("folio", t.getFolio());
        m.put("estado", t.getEstado().name());
        m.put("eliminado", t.isEliminado());
        m.put("eliminadoMotivo", t.getEliminadoMotivo());
        m.put("estudioId", t.getEstudioId());
        m.put("clinicaId", t.getClinicaId());
        m.put("precioCotizado", t.getPrecioCotizado());
        m.put("fechaCita", t.getFechaCita() == null ? null : t.getFechaCita().toString());
        m.put("canalOrigen", t.getCanalOrigen());
        m.put("datosPendientes", new java.util.ArrayList<>(leerPendientes(t.getDatosPendientes()).keySet()));
        if (t.getPaciente() != null) {
            m.put("paciente", pacienteComoMapa(t.getPaciente()));
        }
        if (t.getEstudioId() != null) {
            estudios.findById(t.getEstudioId()).ifPresent(e -> m.put("estudio", mapaDe(
                    "id", String.valueOf(e.getId()),
                    "nombre", e.getNombre(),
                    "precio", e.getPrecio(),
                    "descripcion", e.getDescripcion())));
        }
        if (t.getClinicaId() != null) {
            clinicas.findById(t.getClinicaId()).ifPresent(c -> m.put("clinica", mapaDe(
                    "id", String.valueOf(c.getId()),
                    "nombre", c.getNombre(),
                    "ciudad", c.getCiudad(),
                    "direccion", c.getDireccion())));
        }
        return m;
    }

    private Map<String, Object> pacienteComoMapa(Paciente p) {
        if (p == null) {
            return null;
        }
        return mapaDe(
                "id", p.getId().toString(),
                "nombre", p.getNombre(),
                "curp", p.getCurp(),
                "telefono", p.getTelefono(),
                "fechaNacimiento", p.getFechaNacimiento() == null ? null : p.getFechaNacimiento().toString());
    }

    /** Mapa LinkedHashMap con pares clave/valor (Map.of no admite nulls). */
    private static Map<String, Object> mapaDe(Object... pares) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pares.length; i += 2) {
            m.put(String.valueOf(pares[i]), pares[i + 1]);
        }
        return m;
    }

    public static String siguientePaso(EstadoTramite estado) {
        return switch (estado) {
            case INICIADO -> "COTIZAR";
            case DATOS_PENDIENTES -> "COMPLETAR_DATOS";
            case COTIZADO -> "SELECCIONAR_CLINICA";
            case CLINICA_SELECCIONADA -> "CONFIRMAR_CITA";
            case CONFIRMADO -> "ACUDIR_A_CITA";
            case ATENDIDO -> "TRAMITE_COMPLETADO";
            case NO_PRESENTADO, NO_REALIZADO, CANCELADO -> "TRAMITE_CERRADO";
        };
    }

    private void exigirEstado(Tramite tramite, EstadoTramite... permitidos) {
        if (!Arrays.asList(permitidos).contains(tramite.getEstado())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Transicion no permitida desde " + tramite.getEstado() + "; permitidos: " + Arrays.toString(permitidos));
        }
    }

    private String generarFolio() {
        long n = tramites.count() + 1;
        String folio = String.format("SD-2026-%04d", n);
        while (tramites.existsByFolio(folio)) {
            n++;
            folio = String.format("SD-2026-%04d", n);
        }
        return folio;
    }

    private Map<String, String> leerPendientes(String jsonPendientes) {
        if (jsonPendientes == null || jsonPendientes.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(jsonPendientes, new TypeReference<Map<String, String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "datosPendientes con JSON invalido");
        }
    }

    /**
     * Mensaje legible de un evento para el timeline del Journey: la
     * nota de una edicion o el motivo de una cancelacion / eliminacion
     * / no realizado, sacados del payload. Sin esto esos datos quedaban
     * escondidos en el JSON del payload y el timeline solo mostraba el
     * tipo crudo. Null cuando el evento no trae texto que mostrar.
     */
    private String mensajeDeEvento(String tipo, String payload) {
        if (payload == null || payload.isBlank()) return null;
        Map<String, Object> p;
        try {
            p = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            return null;
        }
        Object nota = p.get("nota");
        Object motivo = p.get("motivo");
        Object cambios = p.get("cambios");
        Object mensaje = p.get("mensaje");
        if (TipoEvento.TRAMITE_EDITADO.equals(tipo)) {
            String texto = "Trámite editado por el asesor."
                    + (nota != null ? " Nota: " + nota : "")
                    + (cambios != null ? " Cambios: " + cambios : "");
            return texto;
        }
        if (TipoEvento.TRAMITE_CANCELADO.equals(tipo)) {
            return "Trámite cancelado por el asesor." + (motivo != null ? " Motivo: " + motivo : "");
        }
        if (TipoEvento.TRAMITE_ELIMINADO.equals(tipo)) {
            return "Trámite eliminado por el asesor." + (motivo != null ? " Motivo: " + motivo : "");
        }
        if (TipoEvento.NO_REALIZADO.equals(tipo) && motivo != null) {
            return "Motivo: " + motivo;
        }
        if (mensaje != null) return String.valueOf(mensaje);
        return null;
    }

    private String json(Map<String, String> mapa) {
        try {
            return objectMapper.writeValueAsString(mapa);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo serializar el payload a JSON");
        }
    }

    /** Como json(), pero admite valores no String (p. ej. el boolean de tramiteExistente en el 409 de duplicado). */
    private String jsonObjeto(Map<String, Object> mapa) {
        try {
            return objectMapper.writeValueAsString(mapa);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo serializar el payload a JSON");
        }
    }
}
