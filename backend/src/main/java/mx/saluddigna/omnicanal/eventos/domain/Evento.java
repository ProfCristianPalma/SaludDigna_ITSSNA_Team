package mx.saluddigna.omnicanal.eventos.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento append-only: una vez escrito nunca se actualiza ni se borra.
 * Es la columna vertebral del sistema: el timeline / journey del
 * paciente se reconstruye leyendo estos eventos en orden.
 */
@Entity
@Table(name = "eventos")
public class Evento {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tramite_id", nullable = false)
    private UUID tramiteId;

    @Column(nullable = false)
    private String tipo;

    @Column(nullable = false)
    private String canal;

    @Column(nullable = false)
    private String actor;

    /** JSON libre con el detalle del evento. TEXT (no JSONB) para compatibilidad H2/PostgreSQL. */
    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false, updatable = false)
    private Instant timestamp;

    protected Evento() {
    }

    public Evento(UUID tramiteId, String tipo, String canal, String actor, String payload) {
        this.tramiteId = tramiteId;
        this.tipo = tipo;
        this.canal = canal;
        this.actor = actor;
        this.payload = payload;
    }

    @PrePersist
    void onCreate() {
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getTramiteId() {
        return tramiteId;
    }

    public String getTipo() {
        return tipo;
    }

    public String getCanal() {
        return canal;
    }

    public String getActor() {
        return actor;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
