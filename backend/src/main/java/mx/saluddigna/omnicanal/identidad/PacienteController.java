package mx.saluddigna.omnicanal.identidad;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mx.saluddigna.omnicanal.identidad.domain.Paciente;
import mx.saluddigna.omnicanal.tramite.TramiteService;
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
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/pacientes")
public class PacienteController {

    private final PacienteService service;
    private final TramiteService tramites;

    public PacienteController(PacienteService service, TramiteService tramites) {
        this.service = service;
        this.tramites = tramites;
    }

    @PostMapping
    public ResponseEntity<Paciente> registrar(@Valid @RequestBody Paciente paciente) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.registrar(paciente));
    }

    /**
     * GET /api/pacientes?telefono=... [&fechaNacimiento=yyyy-MM-dd]
     * Con fecha de nacimiento verifica identidad; sin ella solo resuelve por telefono.
     */
    @GetMapping(params = "telefono")
    public Paciente buscarPorTelefono(
            @RequestParam String telefono,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaNacimiento) {
        if (fechaNacimiento != null) {
            return service.resolverPorTelefonoYFechaNacimiento(telefono, fechaNacimiento)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Paciente no encontrado o fecha de nacimiento incorrecta"));
        }
        return service.buscarPorTelefono(telefono)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Paciente no encontrado con telefono " + telefono));
    }

    /** GET /api/pacientes/buscar?nombre=Ana → pacientes cuyo nombre contiene el texto. */
    @GetMapping("/buscar")
    public List<Paciente> buscarPorNombre(@RequestParam String nombre) {
        return service.buscarPorNombre(nombre);
    }

    /** GET /api/pacientes?curp=GALA900514MDFRPN00 → el paciente de esa CURP (404 si no existe). */
    @GetMapping(params = "curp")
    public Paciente buscarPorCurp(@RequestParam String curp) {
        return service.buscarPorCurp(curp)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Paciente no encontrado con CURP " + curp));
    }

    /** GET /api/pacientes → todos los pacientes (lista para el asesor). */
    @GetMapping
    public List<Paciente> listarTodos() {
        return service.listarTodos();
    }

    @GetMapping("/{id}")
    public Paciente buscarPorId(@PathVariable UUID id) {
        return service.buscarPorId(id);
    }

    /**
     * GET /api/pacientes/{id}/tramites → todos los trámites del paciente,
     * del más reciente al más viejo, como mapas planos (mismo formato
     * que /api/tramites/folio/{folio} y que el journey: paciente,
     * estudio y clínica ya resueltos, nada de entidades JPA).
     * 404 si el paciente no existe.
     */
    @GetMapping("/{id}/tramites")
    public List<Map<String, Object>> tramitesDePaciente(@PathVariable UUID id) {
        service.buscarPorId(id);
        return tramites.listarDePacienteComoMapa(id);
    }

    public record ActualizarPacienteRequest(
            String nombre,
            String curp,
            String telefono,
            String email,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaNacimiento,
            /** Folio del tramite en contexto (opcional): el cambio se registra en su log. */
            UUID tramiteId) {
    }

    /**
     * PUT /api/pacientes/{id} → edita nombre/CURP/teléfono/email/fecha de
     * nacimiento (asesor / Call Center). La contraseña NO se toca
     * desde aquí. 409 si la CURP o el teléfono chocan con otro paciente.
     * telefono "" (vacío) BORRA el teléfono; telefono ausente no lo toca.
     * curp vacía o ausente NO borra la CURP.
     */
    @PutMapping("/{id}")
    public Paciente actualizar(@PathVariable UUID id, @RequestBody ActualizarPacienteRequest req) {
        return service.actualizar(
                id, req.nombre(), req.telefono(), req.email(), req.fechaNacimiento(), req.tramiteId(), req.curp());
    }
}
