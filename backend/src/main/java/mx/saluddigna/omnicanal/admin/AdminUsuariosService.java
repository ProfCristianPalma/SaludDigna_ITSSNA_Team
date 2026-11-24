package mx.saluddigna.omnicanal.admin;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mx.saluddigna.omnicanal.catalogo.CitaRepository;
import mx.saluddigna.omnicanal.eventos.EventoRepository;
import mx.saluddigna.omnicanal.identidad.PacienteRepository;
import mx.saluddigna.omnicanal.identidad.TelefonoUtil;
import mx.saluddigna.omnicanal.identidad.domain.Paciente;
import mx.saluddigna.omnicanal.seguridad.PasswordService;
import mx.saluddigna.omnicanal.tramite.TramiteRepository;
import mx.saluddigna.omnicanal.tramite.domain.Tramite;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Administración de usuarios (solo por URL: /admin/usuarios).
 *
 * Todo es Postgres, tabla pacientes: nada se guarda en memoria ni en
 * caché. Un usuario creado aquí sobrevive a reinicios del backend y a
 * `docker compose down`; solo `docker compose down -v` borra la BD.
 *
 * Borrado: `tramites.paciente_id` es una FK sin ON DELETE CASCADE, y
 * `citas.tramite_id` también referencia a `tramites`, así que borrar
 * un paciente con folios a secas fallaría por violación de FK. Por eso
 * el borrado es en cascada y en la misma transacción: primero las citas
 * de sus trámites, luego los eventos de esos trámites (su `tramite_id`
 * no tiene FK, pero quedarían huérfanos), luego los trámites y por
 * último el paciente. Si algo falla, todo se revierte.
 */
@Service
@Transactional
public class AdminUsuariosService {

    public record UsuarioAdminDTO(
            UUID id,
            String nombre,
            String telefono,
            String email,
            LocalDate fechaNacimiento,
            java.time.Instant createdAt,
            long cantidadFolios,
            boolean tienePassword) {
    }

    private final PacienteRepository pacientes;
    private final TramiteRepository tramites;
    private final EventoRepository eventos;
    private final CitaRepository citas;
    private final PasswordService passwords;

    public AdminUsuariosService(PacienteRepository pacientes, TramiteRepository tramites,
                                EventoRepository eventos, CitaRepository citas,
                                PasswordService passwords) {
        this.pacientes = pacientes;
        this.tramites = tramites;
        this.eventos = eventos;
        this.citas = citas;
        this.passwords = passwords;
    }

    @Transactional(readOnly = true)
    public List<UsuarioAdminDTO> listar() {
        return pacientes.findAll().stream()
                .sorted(Comparator.comparing(
                        Paciente::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .map(this::aDto)
                .toList();
    }

    public UsuarioAdminDTO crear(String nombre, String telefono, String email,
                                 LocalDate fechaNacimiento, String password) {
        validarNombre(nombre);
        String tel = normalizarTelefonoObligatorio(telefono);
        if (password == null || password.length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La contraseña debe tener al menos 6 caracteres");
        }
        if (pacientes.existsByTelefono(tel)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya existe un usuario con el teléfono " + tel);
        }
        Paciente paciente = new Paciente(nombre.trim(), tel, limpiarEmail(email), fechaNacimiento);
        paciente.setPasswordHash(passwords.hash(password));
        return aDto(pacientes.save(paciente));
    }

    public UsuarioAdminDTO actualizar(UUID id, String nombre, String telefono, String email,
                                      LocalDate fechaNacimiento, String password) {
        Paciente paciente = buscar(id);
        if (nombre != null && !nombre.isBlank()) {
            paciente.setNombre(nombre.trim());
        }
        if (telefono != null && !telefono.isBlank()) {
            String tel = TelefonoUtil.normalizar(telefono);
            if (tel.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El teléfono es obligatorio");
            }
            pacientes.findByTelefono(tel).ifPresent(otro -> {
                if (!otro.getId().equals(id)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Ya existe otro usuario con el teléfono " + tel);
                }
            });
            paciente.setTelefono(tel);
        }
        if (email != null) {
            paciente.setEmail(limpiarEmail(email));
        }
        if (fechaNacimiento != null) {
            paciente.setFechaNacimiento(fechaNacimiento);
        }
        // La contraseña solo se cambia si se envía una nueva.
        if (password != null && !password.isBlank()) {
            if (password.length() < 6) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "La contraseña debe tener al menos 6 caracteres");
            }
            paciente.setPasswordHash(passwords.hash(password));
        }
        return aDto(pacientes.save(paciente));
    }

    /**
     * Borra el usuario y, en cascada, sus folios (trámites), sus citas y
     * sus eventos. Devuelve cuántos folios se borraron con él.
     */
    public Map<String, Long> eliminar(UUID id) {
        Paciente paciente = buscar(id);
        List<Tramite> folios = tramites.findByPacienteIdOrderByCreatedAtDesc(id);
        for (Tramite tramite : folios) {
            citas.findByTramiteId(tramite.getId()).ifPresent(citas::delete);
            eventos.deleteAll(eventos.findByTramiteIdOrderByTimestampAsc(tramite.getId()));
        }
        tramites.deleteAll(folios);
        pacientes.delete(paciente);
        return Map.of("foliosEliminados", (long) folios.size());
    }

    @Transactional(readOnly = true)
    public UsuarioAdminDTO buscarDto(UUID id) {
        return aDto(buscar(id));
    }

    private Paciente buscar(UUID id) {
        return pacientes.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Usuario no encontrado: " + id));
    }

    private UsuarioAdminDTO aDto(Paciente paciente) {
        return new UsuarioAdminDTO(
                paciente.getId(),
                paciente.getNombre(),
                paciente.getTelefono(),
                paciente.getEmail(),
                paciente.getFechaNacimiento(),
                paciente.getCreatedAt(),
                tramites.countByPacienteId(paciente.getId()),
                paciente.getPasswordHash() != null && !paciente.getPasswordHash().isBlank());
    }

    private void validarNombre(String nombre) {
        if (nombre == null || nombre.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre es obligatorio");
        }
    }

    private String normalizarTelefonoObligatorio(String telefono) {
        if (telefono == null || telefono.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El teléfono es obligatorio");
        }
        String tel = TelefonoUtil.normalizar(telefono);
        if (tel.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El teléfono es obligatorio");
        }
        return tel;
    }

    /** Email vacío -> null (la columna admite null y @Email no acepta texto basura). */
    private String limpiarEmail(String email) {
        if (email == null) {
            return null;
        }
        String limpio = email.trim();
        if (limpio.isEmpty()) {
            return null;
        }
        if (!limpio.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El correo electrónico no tiene un formato válido");
        }
        return limpio;
    }
}
