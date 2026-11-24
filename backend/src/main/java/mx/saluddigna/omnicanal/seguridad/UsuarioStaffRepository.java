package mx.saluddigna.omnicanal.seguridad;

import java.util.Optional;
import java.util.UUID;
import mx.saluddigna.omnicanal.seguridad.domain.UsuarioStaff;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsuarioStaffRepository extends JpaRepository<UsuarioStaff, UUID> {

    Optional<UsuarioStaff> findByUsuario(String usuario);

    boolean existsByUsuario(String usuario);

    long countByRol(String rol);
}
