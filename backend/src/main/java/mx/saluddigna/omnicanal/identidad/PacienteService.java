package mx.saluddigna.omnicanal.identidad;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mx.saluddigna.omnicanal.identidad.domain.Paciente;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class PacienteService {

    private final PacienteRepository repository;

    public PacienteService(PacienteRepository repository) {
        this.repository = repository;
    }

    /** Registra un paciente nuevo. El telefono es unico: si ya existe, 409. */
    public Paciente registrar(Paciente paciente) {
        if (paciente.getTelefono() != null && repository.existsByTelefono(paciente.getTelefono())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya existe un paciente con el telefono " + paciente.getTelefono());
        }
        return repository.save(paciente);
    }

    /**
     * Resuelve la identidad por telefono + fecha de nacimiento.
     * Es la verificacion de identidad que usa el Call Center / WhatsApp
     * antes de mostrar datos de un tramite: telefono solo no basta.
     */
    @Transactional(readOnly = true)
    public Optional<Paciente> resolverPorTelefonoYFechaNacimiento(String telefono, LocalDate fechaNacimiento) {
        if (telefono == null || fechaNacimiento == null) {
            return Optional.empty();
        }
        return repository.findByTelefonoAndFechaNacimiento(telefono, fechaNacimiento);
    }

    @Transactional(readOnly = true)
    public Optional<Paciente> buscarPorTelefono(String telefono) {
        return repository.findByTelefono(telefono);
    }

    /** Pacientes cuyo nombre contiene el texto (sin importar mayusculas). */
    @Transactional(readOnly = true)
    public List<Paciente> buscarPorNombre(String nombre) {
        if (nombre == null || nombre.isBlank()) {
            return List.of();
        }
        return repository.findByNombreContainingIgnoreCase(nombre.trim());
    }

    @Transactional(readOnly = true)
    public List<Paciente> listarTodos() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public Paciente buscarPorId(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Paciente no encontrado: " + id));
    }
}
