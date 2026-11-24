package mx.saluddigna.omnicanal.seguridad;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Service;

/**
 * Hash de contraseñas con PBKDF2WithHmacSHA256, solo con el JDK
 * (sin dependencias nuevas).
 *
 * Formato guardado: {@code pbkdf2$65536$<saltBase64>$<hashBase64>}
 * (salt aleatorio de 16 bytes, 65536 iteraciones, hash de 256 bits).
 *
 * Grado DEMO para el hackatón, no producción: para producción se usaría
 * bcrypt/argon2 con un coste mayor y rotación de secretos. Aquí lo
 * importante es que la contraseña NUNCA se guarda en texto plano en
 * Postgres y que verificarla no revela el hash (comparación en tiempo
 * constante con {@link MessageDigest#isEqual}).
 */
@Service
public class PasswordService {

    private static final String ALGORITMO = "PBKDF2WithHmacSHA256";
    private static final int ITERACIONES = 65536;
    private static final int LONGITUD_HASH_BITS = 256;
    private static final int LONGITUD_SALT_BYTES = 16;

    public String hash(String password) {
        byte[] salt = new byte[LONGITUD_SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] hash = derivar(password, salt);
        Base64.Encoder encoder = Base64.getEncoder();
        return "pbkdf2$" + ITERACIONES + "$" + encoder.encodeToString(salt)
                + "$" + encoder.encodeToString(hash);
    }

    /** true si la contraseña en claro corresponde al hash guardado. */
    public boolean verificar(String password, String guardado) {
        if (password == null || guardado == null || guardado.isBlank()) {
            return false;
        }
        String[] partes = guardado.split("\\$");
        if (partes.length != 4 || !"pbkdf2".equals(partes[0])) {
            return false;
        }
        int iteraciones;
        try {
            iteraciones = Integer.parseInt(partes[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        Base64.Decoder decoder = Base64.getDecoder();
        byte[] salt;
        byte[] esperado;
        try {
            salt = decoder.decode(partes[2]);
            esperado = decoder.decode(partes[3]);
        } catch (IllegalArgumentException e) {
            return false;
        }
        byte[] actual = derivar(password, salt, iteraciones, esperado.length * 8);
        return MessageDigest.isEqual(esperado, actual);
    }

    private byte[] derivar(String password, byte[] salt) {
        return derivar(password, salt, ITERACIONES, LONGITUD_HASH_BITS);
    }

    private byte[] derivar(String password, byte[] salt, int iteraciones, int longitudBits) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iteraciones, longitudBits);
            return SecretKeyFactory.getInstance(ALGORITMO).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("No se pudo derivar el hash de contraseña", e);
        }
    }
}
