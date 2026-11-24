package mx.saluddigna.omnicanal.seguridad;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** JWT HS256 con jjwt 0.12.x. El secreto sale de la variable de entorno JWT_SECRET. */
@Service
public class JwtService {

    private final SecretKey key;
    private static final long EXPIRACION_MS = 8 * 60 * 60 * 1000L; // 8 horas

    public JwtService(@Value("${JWT_SECRET:salud-digna-demo-secret-cambiar-en-produccion}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generarToken(String usuario, String rol) {
        Date ahora = new Date();
        return Jwts.builder()
                .subject(usuario)
                .claim("rol", rol)
                .issuedAt(ahora)
                .expiration(new Date(ahora.getTime() + EXPIRACION_MS))
                .signWith(key)
                .compact();
    }

    public boolean esValido(String token) {
        try {
            extraerClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String extraerUsuario(String token) {
        return extraerClaims(token).getSubject();
    }

    public String extraerRol(String token) {
        Object rol = extraerClaims(token).get("rol");
        return rol == null ? null : rol.toString();
    }

    private Claims extraerClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
