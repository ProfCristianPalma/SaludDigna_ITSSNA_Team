package mx.saluddigna.omnicanal.tramite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Arrays;
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
import mx.saluddigna.omnicanal.identidad.PacienteRepository;
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
        String canal = canalOrigen == null || canalOrigen.isBlank() ? "WEB" : canalOrigen;
        Tramite tramite = new Tramite(generarFolio(), paciente, estudioId, canal);
        tramite = tramites.save(tramite);
        eventos.registrar(tramite.getId(), TipoEvento.TRAMITE_INICIADO, canal, "PACIENTE",
                json(Map.of("folio", tramite.getFolio(), "estudioId", String.valueOf(estudioId))));
        return tramite;
    }

    public Tramite cotizar(UUID tramiteId, Long estudioId) {
        Tramite tramite = buscar(tramiteId);
        exigirEstado(tramite, EstadoTramite.INICIADO, EstadoTramite.DATOS_PENDIENTES);
        Estudio estudio = estudios.findById(estudioId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Estudio no encontrado: " + estudioId));
        tramite.setEstudioId(estudio.getId());
        tramite.setPrecioCotizado(estudio.getPrecio());
        tramite.setEstado(EstadoTramite.COTIZADO);
        tramite = tramites.save(tramite);
        eventos.registrar(tramite.getId(), TipoEvento.COTIZACION_CREADA, tramite.getCanalOrigen(), "SISTEMA",
                json(Map.of("estudioId", String.valueOf(estudio.getId()),
                        "estudio", estudio.getNombre(),
                        "precio", estudio.getPrecio().toPlainString())));
        return tramite;
    }

    public Tramite seleccionarClinica(UUID tramiteId, Long clinicaId) {
        Tramite tramite = buscar(tramiteId);
        exigirEstado(tramite, EstadoTramite.COTIZADO);
        Clinica clinica = clinicas.findById(clinicaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Clinica no encontrada: " + clinicaId));
        tramite.setClinicaId(clinica.getId());
        tramite.setEstado(EstadoTramite.CLINICA_SELECCIONADA);
        tramite = tramites.save(tramite);
        eventos.registrar(tramite.getId(), TipoEvento.CLINICA_SELECCIONADA, tramite.getCanalOrigen(), "PACIENTE",
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
        Tramite tramite = buscar(tramiteId);
        exigirEstado(tramite, EstadoTramite.CLINICA_SELECCIONADA);
        tramite.setClinicaId(null);
        tramite.setEstado(EstadoTramite.COTIZADO);
        tramite = tramites.save(tramite);
        eventos.registrar(tramite.getId(), TipoEvento.CLINICA_SELECCIONADA, tramite.getCanalOrigen(), "PACIENTE",
                json(Map.of("accion", "cambiar_clinica")));
        return tramite;
    }

    public Tramite confirmar(UUID tramiteId, LocalDateTime fechaCita) {
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
        if (tramite.getClinicaId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El tramite no tiene clinica seleccionada");
        }
        Clinica clinica = clinicas.findById(tramite.getClinicaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Clinica no encontrada"));
        // Disponibilidad sin empalmes: el hueco es (clinica, fecha, hora).
        // Si otra cita CONFIRMADA ya ocupa ese hueco, se rechaza con 409
        // y el mensaje dice cual es el hueco ocupado. La propia cita del
        // tramite (si se reconfirma tras cambiar de clinica) no cuenta.
        citas.findByClinicaIdAndFechaHoraAndEstado(tramite.getClinicaId(), fechaCita, "CONFIRMADA")
                .filter(otra -> !otra.getTramite().getId().equals(tramiteId))
                .ifPresent(otra -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Ese horario ya esta ocupado en " + clinica.getNombre() + " el "
                                    + fechaCita.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                                    + " a las " + fechaCita.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                                    + ". Elige otra fecha u hora.");
                });
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
        eventos.registrar(tramite.getId(), TipoEvento.CITA_CONFIRMADA, tramite.getCanalOrigen(), "PACIENTE",
                json(Map.of("fechaCita", fechaCita.toString(), "clinica", clinica.getNombre())));
        return tramite;
    }

    /**
     * ¿El hueco (clinica, fecha, hora) esta ocupado por otra cita
     * CONFIRMADA? `exceptoTramiteId` (opcional) excluye la cita propia
     * del tramite que esta reconfirmando.
     */
    @Transactional(readOnly = true)
    public boolean huecoOcupado(Long clinicaId, LocalDateTime fechaHora, UUID exceptoTramiteId) {
        return citas.findByClinicaIdAndFechaHoraAndEstado(clinicaId, fechaHora, "CONFIRMADA")
                .filter(c -> exceptoTramiteId == null || !c.getTramite().getId().equals(exceptoTramiteId))
                .isPresent();
    }

    /** Horas ocupadas ("HH:mm") de una clinica en un dia, por citas CONFIRMADAS. */
    @Transactional(readOnly = true)
    public List<String> horasOcupadasDelDia(Long clinicaId, java.time.LocalDate fecha) {
        java.time.LocalDateTime desde = fecha.atStartOfDay();
        java.time.LocalDateTime hasta = fecha.plusDays(1).atStartOfDay();
        return citas.findByClinicaIdAndFechaHoraBetween(clinicaId, desde, hasta).stream()
                .filter(c -> "CONFIRMADA".equals(c.getEstado()))
                .map(c -> c.getFechaHora().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")))
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Completa un dato pendiente (ej. CURP, orden medica). Los pendientes
     * viven como JSON en datosPendientes; al vaciarse, un tramite en
     * DATOS_PENDIENTES vuelve a INICIADO para poder cotizar.
     */
    public Tramite completarDatoPendiente(UUID tramiteId, String clave, String valor) {
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
        eventos.registrar(tramite.getId(), TipoEvento.DATO_PENDIENTE_COMPLETADO, tramite.getCanalOrigen(), "PACIENTE",
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

    /** Todos los tramites en un estado dado, para la recepcion de clinica y tableros. */
    @Transactional(readOnly = true)
    public List<Tramite> listarPorEstado(EstadoTramite estado) {
        return tramites.findByEstado(estado);
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
        return tramites.findAll();
    }

    /** Estados terminales: el tramite ya se cerro y no vuelve a la lista de activos. */
    public static final List<EstadoTramite> ESTADOS_TERMINALES =
            List.of(EstadoTramite.ATENDIDO, EstadoTramite.NO_PRESENTADO, EstadoTramite.NO_REALIZADO);

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
        return tramites.findByPacienteIdOrderByCreatedAtDesc(paciente.getId());
    }

    /**
     * Trámites de un paciente como mapas planos (mismo formato que
     * buscarPorFolioComoMapa / journey: paciente, estudio y clínica ya
     * resueltos). Lo usa GET /api/pacientes/{id}/tramites.
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
            case NO_PRESENTADO, NO_REALIZADO -> "TRAMITE_CERRADO";
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

    private String json(Map<String, String> mapa) {
        try {
            return objectMapper.writeValueAsString(mapa);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo serializar el payload a JSON");
        }
    }
}
