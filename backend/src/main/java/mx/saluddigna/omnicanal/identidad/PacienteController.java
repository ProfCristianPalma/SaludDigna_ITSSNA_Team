package mx.saluddigna.omnicanal.identidad;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import mx.saluddigna.omnicanal.identidad.domain.Paciente;
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
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/pacientes")
public class PacienteController {

    private final PacienteService service;

    public PacienteController(PacienteService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Paciente> registrar(@Valid @RequestBody Paciente paciente) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.registrar(paciente));
    }

    /**
     * GET /api/pacientes?telefono=... [&fechaNacimiento=yyyy-MM-dd]
     * Con fecha de nacimiento verifica identidad; sin ella solo resuelve por telefono.
     */
    @GetMapping
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

    @GetMapping("/{id}")
    public Paciente buscarPorId(@PathVariable UUID id) {
        return service.buscarPorId(id);
    }
}
