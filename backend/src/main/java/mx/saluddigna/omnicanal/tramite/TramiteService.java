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

    public Tramite confirmar(UUID tramiteId, LocalDateTime fechaCita) {
        Tramite tramite = buscar(tramiteId);
        exigirEstado(tramite, EstadoTramite.CLINICA_SELECCIONADA);
        if (fechaCita == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fechaCita es obligatoria");
        }
        if (tramite.getClinicaId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El tramite no tiene clinica seleccionada");
        }
        Clinica clinica = clinicas.findById(tramite.getClinicaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Clinica no encontrada"));
        tramite.setFechaCita(fechaCita);
        tramite.setEstado(EstadoTramite.CONFIRMADO);
        tramite = tramites.save(tramite);
        citas.save(new Cita(tramite, fechaCita, clinica, "CONFIRMADA"));
        eventos.registrar(tramite.getId(), TipoEvento.CITA_CONFIRMADA, tramite.getCanalOrigen(), "PACIENTE",
                json(Map.of("fechaCita", fechaCita.toString(), "clinica", clinica.getNombre())));
        return tramite;
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
        if (tramite.getEstado() == EstadoTramite.ATENDIDO) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El tramite ya fue atendido, no se puede recuperar");
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

    @Transactional(readOnly = true)
    public Tramite buscar(UUID tramiteId) {
        return tramites.findById(tramiteId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tramite no encontrado: " + tramiteId));
    }

    /** Busca un tramite por su folio legible (SD-2026-XXXX). 404 si no existe. */
    @Transactional(readOnly = true)
    public Tramite buscarPorFolio(String folio) {
        return tramites.findByFolio(folio)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tramite no encontrado con folio " + folio));
    }

    /** Todos los tramites en un estado dado, para la recepcion de clinica y tableros. */
    @Transactional(readOnly = true)
    public List<Tramite> listarPorEstado(EstadoTramite estado) {
        return tramites.findByEstado(estado);
    }

    @Transactional(readOnly = true)
    public List<Tramite> listarTodos() {
        return tramites.findAll();
    }

    /**
     * Tramites activos (no atendidos) de un paciente, del mas reciente al mas viejo.
     * Un paciente puede tener varios folios activos a la vez, uno por estudio.
     */
    @Transactional(readOnly = true)
    public List<Tramite> listarActivosDe(UUID pacienteId) {
        return tramites.findByPacienteIdAndEstadoNotOrderByCreatedAtDesc(pacienteId, EstadoTramite.ATENDIDO);
    }

    @Transactional(readOnly = true)
    public List<Tramite> listarPorTelefono(String telefono) {
        Paciente paciente = pacientes.findByTelefono(telefono)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Paciente no encontrado con telefono " + telefono));
        return tramites.findByPacienteIdOrderByCreatedAtDesc(paciente.getId());
    }

    @Transactional(readOnly = true)
    public Optional<Tramite> tramiteActivoDe(UUID pacienteId) {
        return listarActivosDe(pacienteId).stream().findFirst();
    }

    /** Vista journey: tramite + paciente + estudio + clinica + timeline + siguiente paso. */
    @Transactional(readOnly = true)
    public Map<String, Object> obtenerJourney(UUID tramiteId) {
        Tramite tramite = buscar(tramiteId);
        Map<String, Object> journey = new LinkedHashMap<>();
        journey.put("tramite", tramite);
        journey.put("paciente", tramite.getPaciente());
        if (tramite.getEstudioId() != null) {
            estudios.findById(tramite.getEstudioId()).ifPresent(e -> journey.put("estudio", e));
        }
        if (tramite.getClinicaId() != null) {
            clinicas.findById(tramite.getClinicaId()).ifPresent(c -> journey.put("clinica", c));
        }
        citas.findByTramiteId(tramiteId).ifPresent(c -> journey.put("cita", c));
        journey.put("timeline", eventos.listarTimeline(tramiteId));
        journey.put("siguientePaso", siguientePaso(tramite.getEstado()));
        return journey;
    }

    public static String siguientePaso(EstadoTramite estado) {
        return switch (estado) {
            case INICIADO -> "COTIZAR";
            case DATOS_PENDIENTES -> "COMPLETAR_DATOS";
            case COTIZADO -> "SELECCIONAR_CLINICA";
            case CLINICA_SELECCIONADA -> "CONFIRMAR_CITA";
            case CONFIRMADO -> "ACUDIR_A_CITA";
            case ATENDIDO -> "TRAMITE_COMPLETADO";
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
