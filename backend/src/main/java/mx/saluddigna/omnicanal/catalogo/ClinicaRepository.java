package mx.saluddigna.omnicanal.catalogo;

import mx.saluddigna.omnicanal.catalogo.domain.Clinica;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClinicaRepository extends JpaRepository<Clinica, Long> {
}
