package mx.saluddigna.omnicanal.seguridad;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Login DEMO para el hackaton (NO produccion): usuarios fijos en codigo,
 * contrasenas en texto plano, sin base de datos de usuarios ni hash.
 * Sirve solo para obtener un JWT y probar los roles en el frontend.
 *
 * Usuarios demo:
 * - ana / ana123 -&gt; PACIENTE
 * - asesor / asesor123 -&gt; ASESOR
 * - admin / admin123 -&gt; ADMINISTRADOR
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Map<String, String[]> USUARIOS_DEMO = Map.of(
            "ana", new String[]{"ana123", "PACIENTE"},
            "asesor", new String[]{"asesor123", "ASESOR"},
            "admin", new String[]{"admin123", "ADMINISTRADOR"});

    private final JwtService jwtService;

    public AuthController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    public record LoginRequest(String usuario, String password, String rol) {
    }

    public record LoginResponse(String token, String usuario, String rol) {
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest req) {
        String[] credenciales = USUARIOS_DEMO.get(req.usuario());
        if (credenciales == null || !credenciales[0].equals(req.password())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario o contrasena incorrectos");
        }
        String rol = credenciales[1];
        if (req.rol() != null && !req.rol().isBlank() && !req.rol().equalsIgnoreCase(rol)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "El rol no corresponde a este usuario");
        }
        return new LoginResponse(jwtService.generarToken(req.usuario(), rol), req.usuario(), rol);
    }
}
