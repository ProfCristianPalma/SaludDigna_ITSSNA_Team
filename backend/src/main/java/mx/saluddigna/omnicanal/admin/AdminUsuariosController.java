package mx.saluddigna.omnicanal.admin;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mx.saluddigna.omnicanal.admin.AdminUsuariosService.UsuarioAdminDTO;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CRUD de usuarios, solo por URL (front: /admin/usuarios, sin botón
 * ni enlace hacia ella). Vista de personal: el front solo la muestra
 * con sesión de asesor o admin (VistaPersonal).
 *
 * Todo persiste en Postgres (tabla pacientes): crear, editar y borrar
 * aquí se guarda de forma permanente, igual que el registro normal.
 */
@RestController
@RequestMapping("/api/admin/usuarios")
public class AdminUsuariosController {

    private final AdminUsuariosService service;

    public AdminUsuariosController(AdminUsuariosService service) {
        this.service = service;
    }

    public record CrearUsuarioRequest(
            String nombre,
            String curp,
            String telefono,
            String email,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaNacimiento,
            String password) {
    }

    public record ActualizarUsuarioRequest(
            String nombre,
            String curp,
            String telefono,
            String email,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaNacimiento,
            String password) {
    }

    /** GET /api/admin/usuarios → todos, con su cantidad de folios. */
    @GetMapping
    public List<UsuarioAdminDTO> listar() {
        return service.listar();
    }

    /** POST /api/admin/usuarios → crea el usuario en la BD. CURP obligatoria; 409 si la CURP o el teléfono ya existen. */
    @PostMapping
    public ResponseEntity<UsuarioAdminDTO> crear(@RequestBody CrearUsuarioRequest req) {
        UsuarioAdminDTO creado = service.crear(
                req.nombre(), req.curp(), req.telefono(), req.email(), req.fechaNacimiento(), req.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(creado);
    }

    /**
     * PUT /api/admin/usuarios/{id} → edita nombre/CURP/teléfono/email/fecha.
     * La contraseña solo se cambia si se envía una nueva (mínimo 6).
     * 409 si la CURP o el teléfono chocan con otro usuario.
     */
    @PutMapping("/{id}")
    public UsuarioAdminDTO actualizar(@PathVariable UUID id, @RequestBody ActualizarUsuarioRequest req) {
        return service.actualizar(
                id, req.nombre(), req.curp(), req.telefono(), req.email(), req.fechaNacimiento(), req.password());
    }

    /**
     * DELETE /api/admin/usuarios/{id} → borra el usuario y, en cascada
     * y en la misma transacción, sus folios, sus citas y sus eventos.
     * Responde {"foliosEliminados": N}.
     */
    @DeleteMapping("/{id}")
    public Map<String, Long> eliminar(@PathVariable UUID id) {
        return service.eliminar(id);
    }
}
