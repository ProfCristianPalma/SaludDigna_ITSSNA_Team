package mx.saluddigna.omnicanal.admin;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import mx.saluddigna.omnicanal.seguridad.PasswordService;
import mx.saluddigna.omnicanal.seguridad.UsuarioStaffRepository;
import mx.saluddigna.omnicanal.seguridad.domain.UsuarioStaff;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * CRUD del personal (solo por URL: /admin/usuarios, sección
 * "Personal — cuentas de acceso").
 *
 * Todo es Postgres, tabla usuarios_staff (migración V6): nada se
 * guarda en memoria ni en caché. El DTO nunca lleva el password_hash
 * (la contraseña ni se muestra ni se devuelve por la API).
 *
 * Borrado: no se puede borrar al último ADMINISTRADOR (el sistema se
 * quedaría sin nadie que gestione el personal) ni la propia cuenta
 * (el usuario actual, sacado del JWT). En esos casos, 409 con mensaje.
 */
@Service
@Transactional
public class AdminStaffService {

    public record StaffDTO(
            UUID id,
            String nombre,
            String usuario,
            String rol,
            Instant createdAt) {
    }

    private final UsuarioStaffRepository staff;
    private final PasswordService passwords;

    public AdminStaffService(UsuarioStaffRepository staff, PasswordService passwords) {
        this.staff = staff;
        this.passwords = passwords;
    }

    @Transactional(readOnly = true)
    public List<StaffDTO> listar() {
        return staff.findAll().stream()
                .sorted(Comparator.comparing(
                        UsuarioStaff::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::aDto)
                .toList();
    }

    public StaffDTO crear(String nombre, String usuario, String password, String rol) {
        validarNombre(nombre);
        String user = normalizarUsuarioObligatorio(usuario);
        String rolNormalizado = normalizarRol(rol);
        if (password == null || password.length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La contraseña debe tener al menos 6 caracteres");
        }
        if (staff.existsByUsuario(user)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya existe una cuenta de personal con el usuario " + user);
        }
        UsuarioStaff cuenta = new UsuarioStaff(nombre.trim(), user, passwords.hash(password), rolNormalizado);
        return aDto(staff.save(cuenta));
    }

    public StaffDTO actualizar(UUID id, String nombre, String usuario, String rol, String password) {
        UsuarioStaff cuenta = buscar(id);
        if (nombre != null && !nombre.isBlank()) {
            cuenta.setNombre(nombre.trim());
        }
        if (usuario != null && !usuario.isBlank()) {
            String user = usuario.trim().toLowerCase();
            staff.findByUsuario(user).ifPresent(otra -> {
                if (!otra.getId().equals(id)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Ya existe otra cuenta de personal con el usuario " + user);
                }
            });
            cuenta.setUsuario(user);
        }
        if (rol != null && !rol.isBlank()) {
            cuenta.setRol(normalizarRol(rol));
        }
        // La contraseña solo se cambia si se envía una nueva.
        if (password != null && !password.isBlank()) {
            if (password.length() < 6) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "La contraseña debe tener al menos 6 caracteres");
            }
            cuenta.setPasswordHash(passwords.hash(password));
        }
        return aDto(staff.save(cuenta));
    }

    /**
     * Borra la cuenta. 409 si es el último ADMINISTRADOR o si es la
     * propia cuenta del usuario que lo pide (usuarioActual, del JWT).
     */
    public void eliminar(UUID id, String usuarioActual) {
        UsuarioStaff cuenta = buscar(id);
        if (cuenta.getUsuario().equalsIgnoreCase(usuarioActual)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No puedes borrar tu propia cuenta");
        }
        if ("ADMINISTRADOR".equals(cuenta.getRol()) && staff.countByRol("ADMINISTRADOR") <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No se puede borrar al último administrador");
        }
        staff.delete(cuenta);
    }

    private UsuarioStaff buscar(UUID id) {
        return staff.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Cuenta de personal no encontrada: " + id));
    }

    private StaffDTO aDto(UsuarioStaff cuenta) {
        return new StaffDTO(
                cuenta.getId(),
                cuenta.getNombre(),
                cuenta.getUsuario(),
                cuenta.getRol(),
                cuenta.getCreatedAt());
    }

    private void validarNombre(String nombre) {
        if (nombre == null || nombre.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre es obligatorio");
        }
    }

    private String normalizarUsuarioObligatorio(String usuario) {
        if (usuario == null || usuario.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El usuario es obligatorio");
        }
        return usuario.trim().toLowerCase();
    }

    /** Acepta "ADMIN" como alias de "ADMINISTRADOR"; si no, 400. */
    private String normalizarRol(String rol) {
        if (rol == null || rol.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El rol es obligatorio");
        }
        String r = rol.trim().toUpperCase();
        if (r.equals("ADMIN")) {
            return "ADMINISTRADOR";
        }
        if (r.equals("ADMINISTRADOR") || r.equals("ASESOR")) {
            return r;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "El rol debe ser ASESOR o ADMINISTRADOR");
    }
}
