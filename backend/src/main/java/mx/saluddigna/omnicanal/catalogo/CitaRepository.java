package mx.saluddigna.omnicanal.catalogo;

import java.util.Optional;
import java.util.UUID;
import mx.saluddigna.omnicanal.catalogo.domain.Cita;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CitaRepository extends JpaRepository<Cita, UUID> {

    Optional<Cita> findByTramiteId(UUID tramiteId);

    /** ¿Hay una cita CONFIRMADA en esa clinica a esa fecha y hora? (disponibilidad, sin empalmes). */
    boolean existsByClinicaIdAndFechaHoraAndEstado(Long clinicaId, java.time.LocalDateTime fechaHora, String estado);

    Optional<Cita> findByClinicaIdAndFechaHoraAndEstado(Long clinicaId, java.time.LocalDateTime fechaHora, String estado);

    /** Citas de una clinica en un dia (para las horas ocupadas). */
    List<Cita> findByClinicaIdAndFechaHoraBetween(Long clinicaId, java.time.LocalDateTime desde, java.time.LocalDateTime hasta);
}
