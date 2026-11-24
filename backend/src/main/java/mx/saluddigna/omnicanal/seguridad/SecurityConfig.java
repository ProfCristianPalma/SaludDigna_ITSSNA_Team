package mx.saluddigna.omnicanal.seguridad;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Autenticacion DEMO para el hackaton (documentado como simplificacion, NO produccion).
 *
 * Reglas:
 * 1. Si viene header Authorization: Bearer &lt;token&gt;, se valida el JWT con JwtService.
 *    Un Bearer invalido o expirado responde 401.
 * 2. Si no hay Bearer, se acepta el header X-Rol: Paciente | Asesor | Administrador
 *    como fallback de demo, para que el frontend pueda probar cada rol sin login.
 * 3. Sin ninguno de los dos, la peticion sigue como anonima (los controladores
 *    no bloquean por rol en esta demo; el rol queda en request attribute "rol").
 *
 * CORS: abierto solo para el frontend local en http://localhost:5173.
 * CSRF: desactivado porque la API es stateless (JWT en header, sin cookies de sesion).
 *
 * No usa spring-boot-starter-security a proposito: el filtro es un
 * OncePerRequestFilter de spring-web, suficiente para esta demo.
 */
@Component
public class SecurityConfig extends OncePerRequestFilter {

    public static final Set<String> ROLES_VALIDOS = Set.of("PACIENTE", "ASESOR", "ADMINISTRADOR");
    private static final String ORIGEN_FRONTEND = "http://localhost:5173";

    private final JwtService jwtService;

    public SecurityConfig(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Access-Control-Allow-Origin", ORIGEN_FRONTEND);
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Rol");
        response.setHeader("Access-Control-Allow-Credentials", "true");

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7);
            if (!jwtService.esValido(token)) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Token invalido o expirado");
                return;
            }
            request.setAttribute("usuario", jwtService.extraerUsuario(token));
            request.setAttribute("rol", jwtService.extraerRol(token));
            if (jwtService.extraerPacienteId(token) != null) {
                request.setAttribute("pacienteId", jwtService.extraerPacienteId(token));
            }
        } else {
            String rolHeader = request.getHeader("X-Rol");
            if (rolHeader != null && !rolHeader.isBlank()) {
                String rol = rolHeader.trim().toUpperCase();
                if (!ROLES_VALIDOS.contains(rol)) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Rol no valido en X-Rol: " + rolHeader);
                    return;
                }
                request.setAttribute("rol", rol);
            }
        }
        chain.doFilter(request, response);
    }
}
