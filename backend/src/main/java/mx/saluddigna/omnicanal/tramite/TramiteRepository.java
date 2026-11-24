package mx.saluddigna.omnicanal.tramite;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mx.saluddigna.omnicanal.tramite.domain.EstadoTramite;
import mx.saluddigna.omnicanal.tramite.domain.Tramite;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TramiteRepository extends JpaRepository<Tramite, UUID> {

    List<Tramite> findByPacienteIdOrderByCreatedAtDesc(UUID pacienteId);

    /** Cantidad de folios (trámites) de un paciente, para el admin de usuarios. */
    long countByPacienteId(UUID pacienteId);

    List<Tramite> findByPacienteIdAndEstadoNotOrderByCreatedAtDesc(UUID pacienteId, EstadoTramite estado);

    Optional<Tramite> findByFolio(String folio);

    List<Tramite> findByEstado(EstadoTramite estado);

    boolean existsByFolio(String folio);
}
