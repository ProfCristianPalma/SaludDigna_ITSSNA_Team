package mx.saluddigna.omnicanal.identidad.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Paciente: identidad unica del paciente en todos los canales.
 * La clave de resolucion omnicanal es la CURP (UNIQUE, V7): el mismo
 * paciente que escribe por WhatsApp es el que llama al Call Center.
 * El telefono es opcional desde V7 (nullable, ya no UNIQUE en BD;
 * la unicidad la sigue imponiendo el servicio cuando hay telefono).
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

    /** CURP normalizada (mayúsculas, sin espacios, 18 caracteres). NULL en pacientes viejos sin CURP. */
    @Column(name = "curp", length = 18)
    private String curp;

    /** Opcional desde V7: un paciente puede registrarse solo con CURP. */
    @Column
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

    /**
     * Contraseña en claro SOLO de entrada (write-only): el asesor la
     * manda al registrar un paciente (POST /api/pacientes) y
     * PacienteService.registrar la convierte en hash PBKDF2 y la
     * descarta; nunca se persiste ni se devuelve en JSON. Sin esto,
     * el registro por asesor no podia poner contrasena (passwordHash
     * es @JsonIgnore y no se puede mandar por JSON) y el paciente
     * creado no podria iniciar sesion con su CURP.
     */
    @Transient
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

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

    public String getCurp() {
        return curp;
    }

    public void setCurp(String curp) {
        this.curp = curp;
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

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
