package mx.saluddigna.omnicanal.tramite;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mx.saluddigna.omnicanal.tramite.domain.EstadoTramite;
import mx.saluddigna.omnicanal.tramite.domain.Tramite;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TramiteRepository extends JpaRepository<Tramite, UUID> {

    List<Tramite> findByPacienteIdOrderByCreatedAtDesc(UUID pacienteId);

    Optional<Tramite> findFirstByPacienteIdAndEstadoNotOrderByCreatedAtDesc(UUID pacienteId, EstadoTramite estado);

    boolean existsByFolio(String folio);
}
