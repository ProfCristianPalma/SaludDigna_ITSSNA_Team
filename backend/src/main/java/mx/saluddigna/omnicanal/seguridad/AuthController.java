package mx.saluddigna.omnicanal.seguridad;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import mx.saluddigna.omnicanal.identidad.CurpUtil;
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
 * Los pacientes se autentican ÚNICAMENTE con su CURP + contraseña,
 * guardada como hash PBKDF2 en {@code pacientes.password_hash}
 * (migración V4). Ni el teléfono ni el primer nombre sirven para
 * entrar: la CURP es la llave omnicanal desde V7. El JWT lleva el
 * claim "pacienteId" para que la App sepa quién está logueado, y su
 * subject es siempre la CURP.
 *
 * El staff (asesor/admin) vive en Postgres, tabla
 * {@code usuarios_staff} (migración V6), sembrado por DataSeeder y
 * gestionado con el CRUD de /api/admin/staff. Su contraseña también
 * es hash PBKDF2 y su rol sale de la BD.
 *
 * Usuarios demo:
 * - Pacientes en BD: CURP + contraseña (ej. GALA900514MDFRPN00 / ana123)
 * - asesor / asesor123 -&gt; ASESOR
 * - admin / admin123 -&gt; ADMINISTRADOR
 * - doctor / doctor123 -&gt; DOCTOR (Alejandro Ruiz)
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
            String curp,
            String telefono,
            String email,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaNacimiento,
            String password) {
    }

    /** paciente va solo cuando el login es de paciente; en staff es null. `nombre` es el nombre del staff (asesor/admin/doctor) o del paciente. */
    public record AuthResponse(String token, String usuario, String rol, Paciente paciente) {
    }

    public record YoResponse(Paciente paciente, String usuario, String rol, String nombre) {
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

        // Paciente en BD: SOLO por CURP (formato válido + findByCurp).
        // Un teléfono ("2220000001") o un primer nombre ("ana") ya no
        // resuelven a nadie: el error es el 401 genérico de siempre.
        if (!CurpUtil.esFormatoValido(usuario)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario o contraseña incorrectos");
        }
        Paciente paciente = pacientes.findByCurp(CurpUtil.normalizar(usuario))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Usuario o contraseña incorrectos"));
        if (paciente.getPasswordHash() == null
                || !passwords.verificar(req.password(), paciente.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario o contraseña incorrectos");
        }
        validarRolPedido(req.rol(), "PACIENTE");
        String usuarioSesion = usuarioSesion(paciente);
        String token = jwtService.generarToken(usuarioSesion, "PACIENTE", paciente.getId());
        return new AuthResponse(token, usuarioSesion, "PACIENTE", paciente);
    }

    /**
     * Registro con CURP obligatoria (V7) y teléfono opcional.
     * La fecha de nacimiento se deriva de la CURP si no viene; si viene
     * y no coincide con la CURP, 400. Si ya existe un paciente por CURP
     * (o por teléfono, cuando se da) sin contraseña, se completa el
     * registro en vez de bloquearlo; con contraseña, 409.
     */
    @PostMapping("/registro")
    public AuthResponse registro(@Valid @RequestBody RegistroRequest req) {
        if (req.nombre() == null || req.nombre().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre es obligatorio");
        }
        String curp = CurpUtil.normalizar(req.curp());
        CurpUtil.validar(curp).ifPresent(motivo -> {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, motivo);
        });
        if (req.email() == null || req.email().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El correo electrónico es obligatorio");
        }
        String email = req.email().trim();
        if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El correo electrónico no tiene un formato válido");
        }
        if (req.password() == null || req.password().length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La contraseña debe tener al menos 6 caracteres");
        }
        LocalDate fechaCurp = CurpUtil.fechaDesdeCurp(curp);
        LocalDate fechaNacimiento = req.fechaNacimiento();
        if (fechaNacimiento == null) {
            fechaNacimiento = fechaCurp;
        } else if (fechaCurp != null && !fechaCurp.equals(fechaNacimiento)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La fecha de nacimiento no coincide con la CURP");
        }
        String telefono = null;
        if (req.telefono() != null && !req.telefono().isBlank()) {
            telefono = mx.saluddigna.omnicanal.identidad.TelefonoUtil.normalizar(req.telefono());
            if (telefono.isBlank()) {
                telefono = null;
            }
        }
        Optional<Paciente> porCurp = pacientes.findByCurp(curp);
        Optional<Paciente> porTelefono = telefono == null
                ? Optional.empty() : pacientes.findByTelefono(telefono);
        Paciente paciente;
        if (porCurp.isPresent()) {
            paciente = completarRegistro(porCurp.get(), req.nombre().trim(), curp,
                    telefono, email, fechaNacimiento, req.password());
        } else if (porTelefono.isPresent()) {
            paciente = completarRegistro(porTelefono.get(), req.nombre().trim(), curp,
                    telefono, email, fechaNacimiento, req.password());
        } else {
            paciente = new Paciente(req.nombre().trim(), telefono, email, fechaNacimiento);
            paciente.setCurp(curp);
            paciente.setPasswordHash(passwords.hash(req.password()));
            paciente = pacientes.save(paciente);
        }
        String usuarioSesion = usuarioSesion(paciente);
        String token = jwtService.generarToken(usuarioSesion, "PACIENTE", paciente.getId());
        return new AuthResponse(token, usuarioSesion, "PACIENTE", paciente);
    }

    /**
     * Completa el registro de una cuenta que ya existía sin contraseña
     * (creada por la Web al cotizar, o por WhatsApp): si ya tiene
     * contraseña, 409. Si no, le pone nombre, CURP, teléfono, email,
     * fecha y contraseña.
     */
    private Paciente completarRegistro(Paciente paciente, String nombre, String curp,
                                       String telefono, String email, LocalDate fechaNacimiento,
                                       String password) {
        if (paciente.getPasswordHash() != null && !paciente.getPasswordHash().isBlank()) {
            String clave = paciente.getCurp() != null && paciente.getCurp().equals(curp)
                    ? "la CURP " + curp : "el teléfono " + telefono;
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya existe un paciente con " + clave);
        }
        paciente.setNombre(nombre);
        paciente.setCurp(curp);
        if (telefono != null) {
            paciente.setTelefono(telefono);
        }
        paciente.setEmail(email);
        if (fechaNacimiento != null) {
            paciente.setFechaNacimiento(fechaNacimiento);
        }
        paciente.setPasswordHash(passwords.hash(password));
        return pacientes.save(paciente);
    }

    /** Identificador de sesión del paciente: siempre su CURP. */
    private static String usuarioSesion(Paciente paciente) {
        return paciente.getCurp();
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
            return new YoResponse(paciente, usuario, rol, paciente.getNombre());
        }
        String nombreStaff = staff.findByUsuario(usuario.toLowerCase())
                .map(UsuarioStaff::getNombre).orElse(usuario);
        return new YoResponse(null, usuario, rol, nombreStaff);
    }

    private void validarRolPedido(String rolPedido, String rolReal) {
        if (rolPedido != null && !rolPedido.isBlank() && !rolPedido.equalsIgnoreCase(rolReal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "El rol no corresponde a este usuario");
        }
    }
}
