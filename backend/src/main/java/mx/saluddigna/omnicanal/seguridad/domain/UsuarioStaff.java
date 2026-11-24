package mx.saluddigna.omnicanal.seguridad.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;

/**
 * Cuenta del personal (asesor / admin) en Postgres (tabla
 * usuarios_staff, migración V6). Antes vivía hardcodeada en
 * AuthController (USUARIOS_STAFF, contraseñas en texto plano en el
 * código); ahora se siembra desde DataSeeder y se gestiona con el
 * CRUD de /api/admin/staff. El hash PBKDF2 nunca sale en JSON.
 */
@Entity
@Table(name = "usuarios_staff")
public class UsuarioStaff {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotBlank
    @Column(nullable = false)
    private String nombre;

    /** Usuario de login, en minúsculas, único (ej. "admin", "asesor"). */
    @NotBlank
    @Column(nullable = false, unique = true)
    private String usuario;

    @JsonIgnore
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /** "ASESOR" o "ADMINISTRADOR". */
    @NotBlank
    @Column(nullable = false)
    private String rol;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UsuarioStaff() {
    }

    public UsuarioStaff(String nombre, String usuario, String passwordHash, String rol) {
        this.nombre = nombre;
        this.usuario = usuario;
        this.passwordHash = passwordHash;
        this.rol = rol;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getUsuario() {
        return usuario;
    }

    public void setUsuario(String usuario) {
        this.usuario = usuario;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getRol() {
        return rol;
    }

    public void setRol(String rol) {
        this.rol = rol;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
