package mx.saluddigna.omnicanal.seguridad;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mx.saluddigna.omnicanal.identidad.PacienteRepository;
import mx.saluddigna.omnicanal.identidad.domain.Paciente;
import mx.saluddigna.omnicanal.seguridad.domain.UsuarioStaff;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Login y registro REALES contra Postgres (hackatón, no producción).
 *
 * Los pacientes se autentican con su teléfono (o su primer nombre, ej.
 * "ana") + contraseña, guardada como hash PBKDF2 en
 * {@code pacientes.password_hash} (migración V4). El JWT lleva el claim
 * "pacienteId" para que la App sepa quién está logueado sin pedir el
 * teléfono a mano.
 *
 * El staff (asesor/admin) vive en Postgres, tabla
 * {@code usuarios_staff} (migración V6), sembrado por DataSeeder y
 * gestionado con el CRUD de /api/admin/staff. Su contraseña también
 * es hash PBKDF2 y su rol sale de la BD.
 *
 * Usuarios demo:
 * - Pacientes en BD: teléfono a 10 dígitos + contraseña (ej. 2220000001 / ana123)
 * - asesor / asesor123 -&gt; ASESOR
 * - admin / admin123 -&gt; ADMINISTRADOR
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JwtService jwtService;
    private final PasswordService passwords;
    private final PacienteRepository pacientes;
    private final UsuarioStaffRepository staff;

    public AuthController(JwtService jwtService, PasswordService passwords,
                          PacienteRepository pacientes, UsuarioStaffRepository staff) {
        this.jwtService = jwtService;
        this.passwords = passwords;
        this.pacientes = pacientes;
        this.staff = staff;
    }

    public record LoginRequest(String usuario, String password, String rol) {
    }

    public record RegistroRequest(
            String nombre,
            String telefono,
            String email,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaNacimiento,
            String password) {
    }

    /** paciente va solo cuando el login es de paciente; en staff es null. */
    public record AuthResponse(String token, String usuario, String rol, Paciente paciente) {
    }

    public record YoResponse(Paciente paciente, String usuario, String rol) {
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody LoginRequest req) {
        if (req.usuario() == null || req.usuario().isBlank()
                || req.password() == null || req.password().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario o contraseña incorrectos");
        }
        String usuario = req.usuario().trim();

        // (c) Staff en Postgres (tabla usuarios_staff, V6): usuario en
        // minúsculas, contraseña verificada contra el hash PBKDF2 y rol
        // desde la BD. Sin paciente (null).
        Optional<UsuarioStaff> cuentaStaff = staff.findByUsuario(usuario.toLowerCase());
        if (cuentaStaff.isPresent()) {
            UsuarioStaff cuenta = cuentaStaff.get();
            if (!passwords.verificar(req.password(), cuenta.getPasswordHash())) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario o contraseña incorrectos");
            }
            validarRolPedido(req.rol(), cuenta.getRol());
            return new AuthResponse(jwtService.generarToken(cuenta.getUsuario(), cuenta.getRol()),
                    cuenta.getUsuario(), cuenta.getRol(), null);
        }

        // (a) y (b) Paciente en BD: por teléfono, o por primer nombre ("ana").
        Paciente paciente = resolverPaciente(usuario)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Usuario o contraseña incorrectos"));
        if (paciente.getPasswordHash() == null
                || !passwords.verificar(req.password(), paciente.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario o contraseña incorrectos");
        }
        validarRolPedido(req.rol(), "PACIENTE");
        String token = jwtService.generarToken(paciente.getTelefono(), "PACIENTE", paciente.getId());
        return new AuthResponse(token, paciente.getTelefono(), "PACIENTE", paciente);
    }

    @PostMapping("/registro")
    public AuthResponse registro(@Valid @RequestBody RegistroRequest req) {
        if (req.nombre() == null || req.nombre().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre es obligatorio");
        }
        if (req.telefono() == null || req.telefono().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El teléfono es obligatorio");
        }
        if (req.password() == null || req.password().length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La contraseña debe tener al menos 6 caracteres");
        }
        String telefono = mx.saluddigna.omnicanal.identidad.TelefonoUtil.normalizar(req.telefono());
        Optional<Paciente> existente = pacientes.findByTelefono(telefono);
        Paciente paciente;
        if (existente.isPresent()) {
            paciente = existente.get();
            if (paciente.getPasswordHash() != null && !paciente.getPasswordHash().isBlank()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Ya existe un paciente con el teléfono " + telefono);
            }
            // Cuenta creada antes por la Web (cotizar sin registro) y sin
            // contraseña: completar el registro en vez de dejarla bloqueada.
            paciente.setNombre(req.nombre().trim());
            if (req.email() != null && !req.email().isBlank()) {
                paciente.setEmail(req.email().trim());
            }
            if (req.fechaNacimiento() != null) {
                paciente.setFechaNacimiento(req.fechaNacimiento());
            }
            paciente.setPasswordHash(passwords.hash(req.password()));
            paciente = pacientes.save(paciente);
        } else {
            paciente = new Paciente(req.nombre().trim(), telefono,
                    req.email() == null ? null : req.email().trim(), req.fechaNacimiento());
            paciente.setPasswordHash(passwords.hash(req.password()));
            paciente = pacientes.save(paciente);
        }
        String token = jwtService.generarToken(paciente.getTelefono(), "PACIENTE", paciente.getId());
        return new AuthResponse(token, paciente.getTelefono(), "PACIENTE", paciente);
    }

    @GetMapping("/yo")
    public YoResponse yo(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Falta el token de sesión");
        }
        String token = authorization.substring(7);
        if (!jwtService.esValido(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token inválido o expirado");
        }
        String usuario = jwtService.extraerUsuario(token);
        String rol = jwtService.extraerRol(token);
        UUID pacienteId = jwtService.extraerPacienteId(token);
        if (pacienteId != null) {
            Paciente paciente = pacientes.findById(pacienteId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                            "El paciente de la sesión ya no existe"));
            return new YoResponse(paciente, usuario, rol);
        }
        return new YoResponse(null, usuario, rol);
    }

    private void validarRolPedido(String rolPedido, String rolReal) {
        if (rolPedido != null && !rolPedido.isBlank() && !rolPedido.equalsIgnoreCase(rolReal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "El rol no corresponde a este usuario");
        }
    }

    /**
     * Resuelve un paciente por teléfono exacto, o por primer nombre
     * ("ana" -&gt; Ana García López). Un teléfono se reconoce porque
     * tiene al menos 7 dígitos.
     */
    private Optional<Paciente> resolverPaciente(String usuario) {
        String digitos = usuario.replaceAll("\\D", "");
        if (digitos.length() >= 7) {
            // Telefono normalizado a 10 digitos: "+52 222..." y "222..." son el mismo paciente.
            Optional<Paciente> porTelefono = pacientes.findByTelefono(
                    mx.saluddigna.omnicanal.identidad.TelefonoUtil.normalizar(usuario));
            if (porTelefono.isPresent()) {
                return porTelefono;
            }
        }
        List<Paciente> porNombre = pacientes.findByNombreContainingIgnoreCase(usuario);
        return porNombre.stream()
                .filter(p -> p.getNombre().split(" ")[0].equalsIgnoreCase(usuario))
                .findFirst()
                .or(() -> porNombre.stream().findFirst());
    }
}
