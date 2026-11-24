package mx.saluddigna.omnicanal.identidad.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Paciente: identidad unica del paciente en todos los canales.
 * La clave de resolucion omnicanal es el telefono (UNIQUE): el mismo
 * paciente que escribe por WhatsApp es el que llama al Call Center.
 */
@Entity
@Table(name = "pacientes")
public class Paciente {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotBlank
    @Column(nullable = false)
    private String nombre;

    @NotBlank
    @Column(nullable = false, unique = true)
    private String telefono;

    @Email
    @Column
    private String email;

    @Past
    @Column(name = "fecha_nacimiento")
    private LocalDate fechaNacimiento;

    /**
     * Hash PBKDF2 de la contraseña (ver PasswordService). Nunca en texto
     * plano y nunca sale en las respuestas JSON (@JsonIgnore).
     * NULL en cuentas creadas antes de V4 o por la Web sin registro.
     */
    @JsonIgnore
    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Paciente() {
    }

    public Paciente(String nombre, String telefono, String email, LocalDate fechaNacimiento) {
        this.nombre = nombre;
        this.telefono = telefono;
        this.email = email;
        this.fechaNacimiento = fechaNacimiento;
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

    public String getTelefono() {
        return telefono;
    }

    public void setTelefono(String telefono) {
        this.telefono = telefono;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public LocalDate getFechaNacimiento() {
        return fechaNacimiento;
    }

    public void setFechaNacimiento(LocalDate fechaNacimiento) {
        this.fechaNacimiento = fechaNacimiento;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }
}
