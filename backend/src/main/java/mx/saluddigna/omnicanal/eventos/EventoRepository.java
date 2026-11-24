package mx.saluddigna.omnicanal.eventos;

import java.util.List;
import java.util.UUID;
import mx.saluddigna.omnicanal.eventos.domain.Evento;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventoRepository extends JpaRepository<Evento, UUID> {

    List<Evento> findByTramiteIdOrderByTimestampAsc(UUID tramiteId);

    /** ¿Ya hay un evento de ese tipo en el tramite? (p. ej. check-in ya registrado). */
    boolean existsByTramiteIdAndTipo(UUID tramiteId, String tipo);
}
