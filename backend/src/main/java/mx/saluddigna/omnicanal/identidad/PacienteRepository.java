package mx.saluddigna.omnicanal.identidad;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mx.saluddigna.omnicanal.identidad.domain.Paciente;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PacienteRepository extends JpaRepository<Paciente, UUID> {

    Optional<Paciente> findByTelefono(String telefono);

    List<Paciente> findByNombreContainingIgnoreCase(String nombre);

    Optional<Paciente> findByTelefonoAndFechaNacimiento(String telefono, LocalDate fechaNacimiento);

    boolean existsByTelefono(String telefono);
}
