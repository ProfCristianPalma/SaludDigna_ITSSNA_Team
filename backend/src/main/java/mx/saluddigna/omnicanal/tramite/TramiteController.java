package mx.saluddigna.omnicanal.tramite;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
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
import org.springframework.web.bind.annotation.PutMapping;
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

    /**
     * Crear un trámite. `pacienteId` (UUID, flujo habitual) o `curp`
     * (flujo invitado de la Web: resuelve o crea al paciente por CURP;
     * si la CURP no existe, `nombre` es obligatorio y la fecha de
     * nacimiento se deriva de la CURP). Al menos uno de los dos es
     * obligatorio (400 si no viene ninguno).
     */
    public record CrearTramiteRequest(UUID pacienteId, String curp, String nombre, String telefono,
                                      Long estudioId, String canalOrigen) {
    }

    /**
     * `canal` (opcional) es el canal REAL donde ocurrió la acción (WEB,
     * APP, WHATSAPP, CALL_CENTER): el evento del Journey lleva ese
     * canal, no el canal de origen del trámite. Sin canal, el servicio
     * usa el de origen (compatibilidad con clientes viejos y seeds).
     */
    public record CotizarRequest(@NotNull Long estudioId, String canal) {
    }

    public record SeleccionarClinicaRequest(@NotNull Long clinicaId, String canal) {
    }

    public record ConfirmarRequest(@NotNull LocalDateTime fechaCita, String canal) {
    }

    public record CompletarDatoRequest(String clave, String valor, String canal) {
    }

    /**
     * Edicion de la reservacion por el asesor: estudio, clinica y/o
     * fecha y hora (los null son "sin cambio"). La nota es obligatoria
     * (400 si viene vacia) y queda en el log con los cambios.
     * Solo tramites no terminales y no eliminados (409 si no).
     */
    public record EditarTramiteRequest(Long estudioId, Long clinicaId, LocalDateTime fechaCita,
                                       String nota, String canal) {
    }

    public record CancelarTramiteRequest(String motivo, String canal) {
    }

    public record EliminarTramiteRequest(String motivo, String canal) {
    }

    public record RegistrarEventoRequest(String tipo, String canal, String actor, String payload) {
    }

    // NOTA 2026-11-25 (lote 37): ningún endpoint de este controlador devuelve la entidad JPA cruda.
    // `Tramite.paciente` es LAZY y Jackson intentaba serializar el proxy de Hibernate
    // (`hibernateLazyInitializer`, un ByteBuddyInterceptor) → HTTP 500 en el Asesor al confirmar
    // y en Nueva reservación al crear/cotizar. Se devuelve el mapa plano de `tramiteComoMapaPorId`
    // (mismo formato que /tramites/folio/{folio} y el Journey); el front ya lo normaliza.
    @PostMapping("/tramites")
    public ResponseEntity<Map<String, Object>> crear(@Valid @RequestBody CrearTramiteRequest req) {
        Tramite creado;
        if (req.pacienteId() != null) {
            creado = service.crearTramite(req.pacienteId(), req.estudioId(), req.canalOrigen());
        } else if (req.curp() != null && !req.curp().isBlank()) {
            creado = service.crearTramitePorCurp(req.curp(), req.nombre(), req.telefono(),
                    req.estudioId(), req.canalOrigen());
        } else {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "pacienteId o curp es obligatorio para crear el trámite");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(service.tramiteComoMapaPorId(creado.getId()));
    }

    @GetMapping("/tramites/{id}")
    public Map<String, Object> buscar(@PathVariable UUID id) {
        return service.tramiteComoMapaPorId(id);
    }

    /**
     * Asesor: edita la reservacion de un folio (estudio, clinica,
     * fecha y hora). Nota obligatoria; 409 si el tramite esta cerrado
     * o eliminado. El estado se recomputa y la cita se sincroniza.
     */
    @PutMapping("/tramites/{id}")
    public Map<String, Object> editar(@PathVariable UUID id, @RequestBody EditarTramiteRequest req) {
        String canal = req.canal() == null || req.canal().isBlank() ? "CALL_CENTER" : req.canal();
        service.editarTramite(id, req.estudioId(), req.clinicaId(), req.fechaCita(), req.nota(), canal);
        return service.tramiteComoMapaPorId(id);
    }

    /**
     * Asesor: cancela un folio con su motivo (obligatorio, 400 si
     * vacio). Estado CANCELADO (terminal), cita en "CANCELADO" (el
     * hueco queda libre) y evento tramite.cancelado en el log.
     */
    @PostMapping("/tramites/{id}/cancelar")
    public Map<String, Object> cancelar(@PathVariable UUID id, @RequestBody CancelarTramiteRequest req) {
        String canal = req == null || req.canal() == null || req.canal().isBlank() ? "CALL_CENTER" : req.canal();
        service.cancelarTramite(id, req == null ? null : req.motivo(), canal);
        return service.tramiteComoMapaPorId(id);
    }

    /**
     * Asesor: elimina un folio con su motivo (obligatorio, 400 si
     * vacio). BORRADO SUAVE: eliminado=true + estado CANCELADO; el
     * folio sale de los listados pero su Journey e historial quedan
     * intactos y consultables por folio (solo lectura).
     */
    @PostMapping("/tramites/{id}/eliminar")
    public Map<String, Object> eliminar(@PathVariable UUID id, @RequestBody EliminarTramiteRequest req) {
        String canal = req == null || req.canal() == null || req.canal().isBlank() ? "CALL_CENTER" : req.canal();
        service.eliminarTramite(id, req == null ? null : req.motivo(), canal);
        return service.tramiteComoMapaPorId(id);
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
     * CAMBIO 2026-11-24: ya no es igualdad exacta de fechaHora, sino
     * solape de intervalos: la hora pedida se toma como una cita nueva
     * de 30 min y está ocupada si se cruza con una cita CONFIRMADA
     * (que ocupa [fechaHora, fechaHora + duracionMin de su estudio)).
     * El contrato ({"clinicaId","fechaHora","ocupado"}) no cambia.
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
     * Parrilla de horarios del día: 20 slots de 30 min (07:00, 07:30, …,
     * 16:30; el último termina 17:00) con su estado, por solape con las
     * citas CONFIRMADAS de esa clínica (cada cita ocupa [fechaHora,
     * fechaHora + duracionMin de su estudio)).
     * GET /api/disponibilidad/parrilla?clinicaId=1&fecha=2026-11-25[&estudioId=1][&exceptoTramiteId=...]
     * `estudioId` fija la duración de la cita nueva (para el solape
     * futuro); si no viene, duracionMin 30. `exceptoTramiteId` excluye
     * la cita propia al reconfirmar.
     * Respuesta: {"clinicaId":1,"fecha":"2026-11-25","estudioId":1,"duracionMin":30,
     * "slots":[{"hora":"07:00","ocupado":false}, …20 slots]}.
     */
    @GetMapping("/disponibilidad/parrilla")
    public Map<String, Object> parrilla(
            @RequestParam Long clinicaId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate fecha,
            @RequestParam(required = false) Long estudioId,
            @RequestParam(required = false) UUID exceptoTramiteId) {
        if (!service.existeClinica(clinicaId)) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Clinica no encontrada: " + clinicaId);
        }
        int duracionMin = service.duracionEstudio(estudioId);
        if (estudioId != null && !service.existeEstudio(estudioId)) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Estudio no encontrado: " + estudioId);
        }
        List<Map<String, Object>> slots = service.parrilla(clinicaId, fecha, estudioId, exceptoTramiteId).stream()
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("hora", s.hora());
                    m.put("ocupado", s.ocupado());
                    return m;
                })
                .toList();
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("clinicaId", clinicaId);
        respuesta.put("fecha", fecha.toString());
        respuesta.put("estudioId", estudioId);
        respuesta.put("duracionMin", duracionMin);
        respuesta.put("slots", slots);
        return respuesta;
    }

    /**
     * Horas ocupadas ("HH:mm") de una clínica en un día.
     * GET /api/disponibilidad/dia?clinicaId=1&fecha=2026-11-25 (fecha en ISO AAAA-MM-DD).
     * CAMBIO 2026-11-24: ya no son las horas exactas de inicio de las
     * citas, sino los inicios de slot de la parrilla (07:00–16:30, cada
     * 30 min) que se solapan con alguna cita CONFIRMADA (una cita de
     * 10:15 de 20 min ocupa 10:00 y 10:30). El contrato (lista de
     * "HH:mm" ordenada) no cambia; el front usa la parrilla.
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
    public List<Map<String, Object>> listar(
            @RequestParam(required = false) String telefono,
            @RequestParam(required = false) mx.saluddigna.omnicanal.tramite.domain.EstadoTramite estado) {
        return service.listarComoMapa(telefono, estado);
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
    public Map<String, Object> cotizar(@PathVariable UUID id, @Valid @RequestBody CotizarRequest req) {
        service.cotizar(id, req.estudioId(), req.canal());
        return service.tramiteComoMapaPorId(id);
    }

    @PostMapping("/tramites/{id}/seleccionar-clinica")
    public Map<String, Object> seleccionarClinica(@PathVariable UUID id, @Valid @RequestBody SeleccionarClinicaRequest req) {
        service.seleccionarClinica(id, req.clinicaId(), req.canal());
        return service.tramiteComoMapaPorId(id);
    }

    @PostMapping("/tramites/{id}/confirmar")
    public Map<String, Object> confirmar(@PathVariable UUID id, @Valid @RequestBody ConfirmarRequest req) {
        service.confirmar(id, req.fechaCita(), req.canal());
        return service.tramiteComoMapaPorId(id);
    }

    /** Clínica: marca la atención como concluida (CONFIRMADO → ATENDIDO) y actualiza la cita. */
    @PostMapping("/tramites/{id}/atender")
    public Map<String, Object> atender(@PathVariable UUID id) {
        service.concluirAtencion(id);
        return service.tramiteComoMapaPorId(id);
    }

    public record MotivoRequest(String motivo) {
    }

    /** Doctor: registra el check-in del paciente (CONFIRMADO, sin cambiar el estado). */
    @PostMapping("/tramites/{id}/check-in")
    public Map<String, Object> checkIn(@PathVariable UUID id) {
        service.registrarCheckIn(id);
        return service.tramiteComoMapaPorId(id);
    }

    /** Doctor: el paciente no se presentó a su cita (CONFIRMADO → NO_PRESENTADO, terminal). */
    @PostMapping("/tramites/{id}/no-presentado")
    public Map<String, Object> noPresentado(@PathVariable UUID id) {
        service.marcarNoPresentado(id);
        return service.tramiteComoMapaPorId(id);
    }

    /** Doctor: el estudio no se pudo realizar (CONFIRMADO → NO_REALIZADO, terminal). El motivo es obligatorio. */
    @PostMapping("/tramites/{id}/no-realizado")
    public Map<String, Object> noRealizado(@PathVariable UUID id, @RequestBody MotivoRequest req) {
        service.marcarNoRealizado(id, req == null ? null : req.motivo());
        return service.tramiteComoMapaPorId(id);
    }

    @PostMapping("/tramites/{id}/completar-dato")
    public Map<String, Object> completarDato(@PathVariable UUID id, @RequestBody CompletarDatoRequest req) {
        service.completarDatoPendiente(id, req.clave(), req.valor(), req.canal());
        return service.tramiteComoMapaPorId(id);
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
