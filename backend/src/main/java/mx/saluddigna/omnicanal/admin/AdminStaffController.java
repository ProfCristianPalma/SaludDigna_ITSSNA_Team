package mx.saluddigna.omnicanal.admin;

import java.util.List;
import java.util.UUID;
import mx.saluddigna.omnicanal.admin.AdminStaffService.StaffDTO;
import mx.saluddigna.omnicanal.seguridad.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * CRUD de cuentas del personal, solo por URL (front: /admin/usuarios,
 * sección "Personal — cuentas de acceso", sin botón ni enlace hacia
 * ella). Vista de personal: el front solo la muestra con sesión de
 * asesor o admin.
 *
 * Todo persiste en Postgres (tabla usuarios_staff, V6). La contraseña
 * nunca se muestra ni se devuelve por la API: el DTO no lleva
 * password_hash, y al editar solo se cambia si se envía una nueva.
 */
@RestController
@RequestMapping("/api/admin/staff")
public class AdminStaffController {

    private final AdminStaffService service;
    private final JwtService jwtService;

    public AdminStaffController(AdminStaffService service, JwtService jwtService) {
        this.service = service;
        this.jwtService = jwtService;
    }

    public record CrearStaffRequest(String nombre, String usuario, String password, String rol) {
    }

    public record ActualizarStaffRequest(String nombre, String usuario, String password, String rol) {
    }

    /** GET /api/admin/staff → todas las cuentas (sin password_hash). */
    @GetMapping
    public List<StaffDTO> listar() {
        return service.listar();
    }

    /** POST /api/admin/staff → crea la cuenta en la BD. 409 si el usuario ya existe. */
    @PostMapping
    public ResponseEntity<StaffDTO> crear(@RequestBody CrearStaffRequest req) {
        StaffDTO creado = service.crear(req.nombre(), req.usuario(), req.password(), req.rol());
        return ResponseEntity.status(HttpStatus.CREATED).body(creado);
    }

    /**
     * PUT /api/admin/staff/{id} → edita nombre/usuario/rol. La
     * contraseña solo se cambia si se envía una nueva (mínimo 6).
     * 409 si el usuario choca con otra cuenta.
     */
    @PutMapping("/{id}")
    public StaffDTO actualizar(@PathVariable UUID id, @RequestBody ActualizarStaffRequest req) {
        return service.actualizar(id, req.nombre(), req.usuario(), req.rol(), req.password());
    }

    /**
     * DELETE /api/admin/staff/{id} → borra la cuenta. 409 si es el
     * último ADMINISTRADOR o si es la propia cuenta del que lo pide
     * (usuario del JWT en el header Authorization).
     */
    @DeleteMapping("/{id}")
    public void eliminar(@PathVariable UUID id,
                         @RequestHeader(value = "Authorization", required = false) String authorization) {
        service.eliminar(id, usuarioActual(authorization));
    }

    private String usuarioActual(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Falta el token de sesión");
        }
        String token = authorization.substring(7);
        if (!jwtService.esValido(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token inválido o expirado");
        }
        return jwtService.extraerUsuario(token);
    }
}
