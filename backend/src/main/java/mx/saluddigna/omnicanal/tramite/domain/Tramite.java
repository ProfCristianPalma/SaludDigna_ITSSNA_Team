package mx.saluddigna.omnicanal.tramite.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import mx.saluddigna.omnicanal.identidad.domain.Paciente;

/**
 * Tramite: un paciente, una sola conversacion. Agrega estudio, clinica,
 * cita y precio, y su estado solo cambia a traves de TramiteService,
 * siempre acompanado de un evento en el log.
 */
@Entity
@Table(name = "tramites")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Tramite {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Folio legible para el paciente, ej. SD-2026-0001. Generado en TramiteService. */
    @Column(nullable = false, unique = true)
    private String folio;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "paciente_id", nullable = false)
    private Paciente paciente;

    @Column(name = "estudio_id")
    private Long estudioId;

    @Column(name = "clinica_id")
    private Long clinicaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoTramite estado;

    @Column(name = "canal_origen", nullable = false)
    private String canalOrigen;

    /** Datos que faltan por capturar, como JSON {"curp": "...", "ordenMedica": "..."}. TEXT para compat H2/Postgres. */
    @Column(name = "datos_pendientes", columnDefinition = "TEXT")
    private String datosPendientes;

    @Column(name = "precio_cotizado", precision = 10, scale = 2)
    private BigDecimal precioCotizado;

    @Column(name = "fecha_cita")
    private LocalDateTime fechaCita;

    /**
     * Borrado suave (V12): TRUE = el folio sale de TODOS los listados
     * pero NO se borra de la BD: su Journey y su historial (eventos)
     * siguen consultables por folio/id, solo lectura. Nunca se borra
     * un tramite en duro: eventos.tramite_id es NOT NULL y el log es
     * append-only.
     */
    @Column(name = "eliminado", nullable = false)
    private boolean eliminado = false;

    /** Causa que escribio el asesor al eliminar (ver eliminarTramite). */
    @Column(name = "eliminado_motivo", columnDefinition = "TEXT")
    private String eliminadoMotivo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Tramite() {
    }

    public Tramite(String folio, Paciente paciente, Long estudioId, String canalOrigen) {
        this.folio = folio;
        this.paciente = paciente;
        this.estudioId = estudioId;
        this.canalOrigen = canalOrigen;
        this.estado = EstadoTramite.INICIADO;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getFolio() {
        return folio;
    }

    public Paciente getPaciente() {
        return paciente;
    }

    public Long getEstudioId() {
        return estudioId;
    }

    public void setEstudioId(Long estudioId) {
        this.estudioId = estudioId;
    }

    public Long getClinicaId() {
        return clinicaId;
    }

    public void setClinicaId(Long clinicaId) {
        this.clinicaId = clinicaId;
    }

    public EstadoTramite getEstado() {
        return estado;
    }

    public void setEstado(EstadoTramite estado) {
        this.estado = estado;
    }

    public String getCanalOrigen() {
        return canalOrigen;
    }

    public String getDatosPendientes() {
        return datosPendientes;
    }

    public void setDatosPendientes(String datosPendientes) {
        this.datosPendientes = datosPendientes;
    }

    public BigDecimal getPrecioCotizado() {
        return precioCotizado;
    }

    public void setPrecioCotizado(BigDecimal precioCotizado) {
        this.precioCotizado = precioCotizado;
    }

    public LocalDateTime getFechaCita() {
        return fechaCita;
    }

    public void setFechaCita(LocalDateTime fechaCita) {
        this.fechaCita = fechaCita;
    }

    public boolean isEliminado() {
        return eliminado;
    }

    public void setEliminado(boolean eliminado) {
        this.eliminado = eliminado;
    }

    public String getEliminadoMotivo() {
        return eliminadoMotivo;
    }

    public void setEliminadoMotivo(String eliminadoMotivo) {
        this.eliminadoMotivo = eliminadoMotivo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
