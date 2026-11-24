package mx.saluddigna.omnicanal.eventos;

import java.util.List;
import java.util.UUID;
import mx.saluddigna.omnicanal.eventos.domain.Evento;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unico punto de escritura del log de eventos.
 * Append-only por diseno: solo hay registrar() y lecturas; no existe
 * ningun metodo de actualizacion ni borrado de eventos.
 */
@Service
@Transactional
public class EventoService {

    private final EventoRepository repository;

    public EventoService(EventoRepository repository) {
        this.repository = repository;
    }

    public Evento registrar(UUID tramiteId, String tipo, String canal, String actor, String payload) {
        return repository.save(new Evento(tramiteId, tipo, canal, actor, payload));
    }

    public Evento registrar(UUID tramiteId, String tipo, String canal, String actor) {
        return registrar(tramiteId, tipo, canal, actor, null);
    }

    @Transactional(readOnly = true)
    public List<Evento> listarTimeline(UUID tramiteId) {
        return repository.findByTramiteIdOrderByTimestampAsc(tramiteId);
    }

    /** ¿Ya hay un evento de ese tipo en el tramite? (p. ej. check-in ya registrado). */
    @Transactional(readOnly = true)
    public boolean tieneEvento(UUID tramiteId, String tipo) {
        return repository.existsByTramiteIdAndTipo(tramiteId, tipo);
    }
}
