package mx.saluddigna.omnicanal.catalogo;

import mx.saluddigna.omnicanal.catalogo.domain.Estudio;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EstudioRepository extends JpaRepository<Estudio, Long> {
}
