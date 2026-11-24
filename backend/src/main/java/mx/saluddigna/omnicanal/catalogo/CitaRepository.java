package mx.saluddigna.omnicanal.catalogo;

import java.util.Optional;
import java.util.UUID;
import mx.saluddigna.omnicanal.catalogo.domain.Cita;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CitaRepository extends JpaRepository<Cita, UUID> {

    Optional<Cita> findByTramiteId(UUID tramiteId);
}
