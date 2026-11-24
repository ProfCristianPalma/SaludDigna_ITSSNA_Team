package mx.saluddigna.omnicanal.tramite;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mx.saluddigna.omnicanal.eventos.EventoService;
import mx.saluddigna.omnicanal.eventos.domain.Evento;
import mx.saluddigna.omnicanal.tramite.domain.Tramite;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TramiteController {

    private final TramiteService service;
    private final EventoService eventos;

    public TramiteController(TramiteService service, EventoService eventos) {
        this.service = service;
        this.eventos = eventos;
    }

    public record CrearTramiteRequest(@NotNull UUID pacienteId, Long estudioId, String canalOrigen) {
    }

    public record CotizarRequest(@NotNull Long estudioId) {
    }

    public record SeleccionarClinicaRequest(@NotNull Long clinicaId) {
    }

    public record ConfirmarRequest(@NotNull LocalDateTime fechaCita) {
    }

    public record CompletarDatoRequest(String clave, String valor) {
    }

    public record RegistrarEventoRequest(String tipo, String canal, String actor, String payload) {
    }

    @PostMapping("/tramites")
    public ResponseEntity<Tramite> crear(@Valid @RequestBody CrearTramiteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.crearTramite(req.pacienteId(), req.estudioId(), req.canalOrigen()));
    }

    @GetMapping("/tramites/{id}")
    public Tramite buscar(@PathVariable UUID id) {
        return service.buscar(id);
    }

    /** Trámite por folio legible, ej. SD-2026-0001 (el {id} de arriba solo acepta UUID).
     * Devuelve el mapa plano (paciente/estudio/clínica resueltos), no la entidad JPA:
     * el asesor y el admin buscaban por folio y la entidad cruda era frágil al serializar. */
    @GetMapping("/tramites/folio/{folio}")
    public Map<String, Object> buscarPorFolio(@PathVariable String folio) {
        return service.buscarPorFolioComoMapa(folio);
    }

    /**
     * Disponibilidad sin empalmes: ¿el hueco (clínica, fecha, hora) ya está
     * ocupado por otra cita CONFIRMADA? GET /api/disponibilidad?clinicaId=1&fechaHora=2026-11-25T10:00
     * (fechaHora en ISO). `exceptoTramiteId` excluye la cita propia al reconfirmar.
     */
    @GetMapping("/disponibilidad")
    public Map<String, Object> disponibilidad(
            @RequestParam Long clinicaId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fechaHora,
            @RequestParam(required = false) UUID exceptoTramiteId) {
        boolean ocupado = service.huecoOcupado(clinicaId, fechaHora, exceptoTramiteId);
        return Map.of("clinicaId", clinicaId, "fechaHora", fechaHora.toString(), "ocupado", ocupado);
    }

    /**
     * Horas ocupadas ("HH:mm") de una clínica en un día.
     * GET /api/disponibilidad/dia?clinicaId=1&fecha=2026-11-25 (fecha en ISO AAAA-MM-DD).
     */
    @GetMapping("/disponibilidad/dia")
    public Map<String, Object> horasOcupadas(
            @RequestParam Long clinicaId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate fecha) {
        return Map.of("clinicaId", clinicaId, "fecha", fecha.toString(),
                "horasOcupadas", service.horasOcupadasDelDia(clinicaId, fecha));
    }

    /**
     * Tablero del doctor: citas CONFIRMADAS de un día (por defecto hoy).
     * GET /api/doctor/citas?fecha=2026-11-25 (fecha en ISO AAAA-MM-DD).
     */
    @GetMapping("/doctor/citas")
    public List<CitaClinicaDTO> citasDoctor(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate fecha) {
        return service.listarCitasDoctor(fecha);
    }

    /**
     * GET /api/tramites?telefono=... → trámites de ese paciente (como antes).
     * GET /api/tramites?estado=CONFIRMADO → trámites en ese estado (recepción de clínica).
     * GET /api/tramites (sin params) → todos.
     */
    @GetMapping("/tramites")
    public List<Tramite> listar(
            @RequestParam(required = false) String telefono,
            @RequestParam(required = false) mx.saluddigna.omnicanal.tramite.domain.EstadoTramite estado) {
        if (estado != null) {
            return service.listarPorEstado(estado);
        }
        if (telefono != null && !telefono.isBlank()) {
            return service.listarPorTelefono(telefono);
        }
        return service.listarTodos();
    }

    /** Registro manual de un evento sobre un tramite (append-only, no cambia el estado por si solo). */
    @PostMapping("/tramites/{id}/eventos")
    public ResponseEntity<Evento> registrarEvento(@PathVariable UUID id, @RequestBody RegistrarEventoRequest req) {
        service.buscar(id);
        String tipo = req.tipo() == null ? "canal.cambiado" : req.tipo();
        String canal = req.canal() == null ? "WEB" : req.canal();
        String actor = req.actor() == null ? "SISTEMA" : req.actor();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(eventos.registrar(id, tipo, canal, actor, req.payload()));
    }

    @PostMapping("/tramites/{id}/cotizar")
    public Tramite cotizar(@PathVariable UUID id, @Valid @RequestBody CotizarRequest req) {
        return service.cotizar(id, req.estudioId());
    }

    @PostMapping("/tramites/{id}/seleccionar-clinica")
    public Tramite seleccionarClinica(@PathVariable UUID id, @Valid @RequestBody SeleccionarClinicaRequest req) {
        return service.seleccionarClinica(id, req.clinicaId());
    }

    @PostMapping("/tramites/{id}/confirmar")
    public Tramite confirmar(@PathVariable UUID id, @Valid @RequestBody ConfirmarRequest req) {
        return service.confirmar(id, req.fechaCita());
    }

    /** Clínica: marca la atención como concluida (CONFIRMADO → ATENDIDO) y actualiza la cita. */
    @PostMapping("/tramites/{id}/atender")
    public Tramite atender(@PathVariable UUID id) {
        return service.concluirAtencion(id);
    }

    public record MotivoRequest(String motivo) {
    }

    /** Doctor: registra el check-in del paciente (CONFIRMADO, sin cambiar el estado). */
    @PostMapping("/tramites/{id}/check-in")
    public Tramite checkIn(@PathVariable UUID id) {
        return service.registrarCheckIn(id);
    }

    /** Doctor: el paciente no se presentó a su cita (CONFIRMADO → NO_PRESENTADO, terminal). */
    @PostMapping("/tramites/{id}/no-presentado")
    public Tramite noPresentado(@PathVariable UUID id) {
        return service.marcarNoPresentado(id);
    }

    /** Doctor: el estudio no se pudo realizar (CONFIRMADO → NO_REALIZADO, terminal). El motivo es obligatorio. */
    @PostMapping("/tramites/{id}/no-realizado")
    public Tramite noRealizado(@PathVariable UUID id, @RequestBody MotivoRequest req) {
        return service.marcarNoRealizado(id, req == null ? null : req.motivo());
    }

    @PostMapping("/tramites/{id}/completar-dato")
    public Tramite completarDato(@PathVariable UUID id, @RequestBody CompletarDatoRequest req) {
        return service.completarDatoPendiente(id, req.clave(), req.valor());
    }

    @GetMapping("/tramites/{id}/timeline")
    public List<Evento> timeline(@PathVariable UUID id) {
        service.buscar(id);
        return eventos.listarTimeline(id);
    }

    @GetMapping("/journey/{tramiteId}")
    public Map<String, Object> journey(@PathVariable UUID tramiteId) {
        return service.obtenerJourney(tramiteId);
    }

    /** Journey por folio legible, ej. SD-2026-0001. */
    @GetMapping("/journey/folio/{folio}")
    public Map<String, Object> journeyPorFolio(@PathVariable String folio) {
        return service.obtenerJourney(service.buscarPorFolio(folio).getId());
    }

    /**
     * Tablero de Clínica: citas como DTOs planos (no entidades JPA).
     * GET /api/clinica/citas?estado=CONFIRMADO (por defecto, si se
     * pasa estado); sin estado trae todos. El endpoint viejo
     * GET /api/tramites?estado= sigue intacto.
     */
    @GetMapping("/clinica/citas")
    public List<CitaClinicaDTO> citasClinica(
            @RequestParam(required = false) String estado) {
        if (estado == null || estado.isBlank()) {
            return service.listarCitasClinica(null);
        }
        return service.listarCitasClinica(
                mx.saluddigna.omnicanal.tramite.domain.EstadoTramite.valueOf(estado.trim().toUpperCase()));
    }
}
