package mx.saluddigna.omnicanal.identidad;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import mx.saluddigna.omnicanal.eventos.EventoService;
import mx.saluddigna.omnicanal.eventos.domain.TipoEvento;
import mx.saluddigna.omnicanal.identidad.domain.Paciente;
import mx.saluddigna.omnicanal.seguridad.PasswordService;
import mx.saluddigna.omnicanal.tramite.TramiteRepository;
import mx.saluddigna.omnicanal.tramite.domain.Tramite;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class PacienteService {

    private final PacienteRepository repository;
    private final EventoService eventos;
    private final TramiteRepository tramites;
    private final ObjectMapper objectMapper;
    private final PasswordService passwords;

    public PacienteService(PacienteRepository repository, EventoService eventos,
                           TramiteRepository tramites, ObjectMapper objectMapper,
                           PasswordService passwords) {
        this.repository = repository;
        this.eventos = eventos;
        this.tramites = tramites;
        this.objectMapper = objectMapper;
        this.passwords = passwords;
    }

    /**
     * Registra un paciente nuevo. La CURP es la llave omnicanal (V7):
     * si trae CURP se valida (formato + fecha real + entidad, 400 si es
     * inválida) y es única (409 si ya existe); si trae teléfono se guarda
     * a 10 dígitos y también es único (409). El teléfono es opcional:
     * un paciente puede registrarse solo con CURP (teléfono null).
     * Si trae CURP y no trae fecha de nacimiento, se deriva de la CURP;
     * si trae ambas y no coinciden, 400.
     */
    public Paciente registrar(Paciente paciente) {
        String curp = CurpUtil.normalizar(paciente.getCurp());
        if (curp != null && curp.isBlank()) {
            curp = null;
        }
        if (curp != null) {
            CurpUtil.validar(curp).ifPresent(motivo -> {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, motivo);
            });
            if (repository.existsByCurp(curp)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Ya existe un paciente con la CURP " + curp);
            }
            paciente.setCurp(curp);
            LocalDate fechaCurp = CurpUtil.fechaDesdeCurp(curp);
            if (paciente.getFechaNacimiento() == null) {
                paciente.setFechaNacimiento(fechaCurp);
            } else if (fechaCurp != null && !fechaCurp.equals(paciente.getFechaNacimiento())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "La fecha de nacimiento no coincide con la CURP");
            }
        } else {
            paciente.setCurp(null);
        }
        if (paciente.getTelefono() != null) {
            String tel = TelefonoUtil.normalizar(paciente.getTelefono());
            paciente.setTelefono(tel == null || tel.isBlank() ? null : tel);
        }
        if (paciente.getTelefono() != null && repository.existsByTelefono(paciente.getTelefono())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya existe un paciente con el telefono " + paciente.getTelefono());
        }
        // Contraseña del registro por asesor (POST /api/pacientes con
        // campo `password`): se guarda SOLO el hash PBKDF2 y la clave
        // en claro se descarta; sin contraseña no se toca el hash (el
        // registro normal por Web/WhatsApp no manda contraseña y el
        // paciente queda sin ella, como antes). Minimo 6 (400 si no).
        // El paciente creado asi inicia sesion con su CURP + esa
        // contrasena (AuthController.login verifica el hash). El
        // registro normal (/api/auth/registro) no se toca.
        if (paciente.getPassword() != null && !paciente.getPassword().isBlank()) {
            if (paciente.getPassword().length() < 6) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "La contraseña debe tener al menos 6 caracteres");
            }
            paciente.setPasswordHash(passwords.hash(paciente.getPassword()));
            paciente.setPassword(null);
        }
        return repository.save(paciente);
    }

    /**
     * Resuelve la identidad por telefono + fecha de nacimiento.
     * Es la verificacion de identidad que usa el Call Center / WhatsApp
     * antes de mostrar datos de un tramite: telefono solo no basta.
     */
    @Transactional(readOnly = true)
    public Optional<Paciente> resolverPorTelefonoYFechaNacimiento(String telefono, LocalDate fechaNacimiento) {
        if (telefono == null || fechaNacimiento == null) {
            return Optional.empty();
        }
        return repository.findByTelefonoAndFechaNacimiento(TelefonoUtil.normalizar(telefono), fechaNacimiento);
    }

    @Transactional(readOnly = true)
    public Optional<Paciente> buscarPorTelefono(String telefono) {
        if (telefono == null || telefono.isBlank()) {
            return Optional.empty();
        }
        String tel = TelefonoUtil.normalizar(telefono);
        if (tel == null || tel.isBlank()) {
            return Optional.empty();
        }
        return repository.findByTelefono(tel);
    }

    /** Resuelve por CURP normalizada (mayúsculas, sin espacios). Vacía o null → vacío. */
    @Transactional(readOnly = true)
    public Optional<Paciente> buscarPorCurp(String curp) {
        if (curp == null || curp.isBlank()) {
            return Optional.empty();
        }
        return repository.findByCurp(CurpUtil.normalizar(curp));
    }

    /** Pacientes cuyo nombre contiene el texto (sin importar mayusculas). */
    @Transactional(readOnly = true)
    public List<Paciente> buscarPorNombre(String nombre) {
        if (nombre == null || nombre.isBlank()) {
            return List.of();
        }
        return repository.findByNombreContainingIgnoreCase(nombre.trim());
    }

    @Transactional(readOnly = true)
    public List<Paciente> listarTodos() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public Paciente buscarPorId(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Paciente no encontrado: " + id));
    }

    /**
     * Edita los datos de un paciente (asesor / Call Center): nombre,
     * CURP, telefono, email y fecha de nacimiento. La contrasena NO se
     * toca desde aqui. La CURP y el telefono siguen siendo unicos
     * (409 si chocan con otro paciente). El cambio queda en el log de
     * eventos del tramite indicado (o del tramite mas reciente del
     * paciente, si no se indica uno): sin folio no hay donde colgar el
     * evento, asi que en ese caso solo se guarda el cambio.
     *
     * Semántica de los opcionales (distinta para CURP y teléfono):
     * - curp null o vacía → no se toca (una CURP asignada NO se borra);
     *   con valor y distinta → se valida formato y unicidad.
     * - telefono null → no se toca; telefono "" (vacío) → se BORRA
     *   (queda en null); con valor → se normaliza y se valida unicidad.
     */
    public Paciente actualizar(UUID id, String nombre, String telefono, String email,
                               LocalDate fechaNacimiento, UUID tramiteId, String curp) {
        Paciente paciente = buscarPorId(id);
        Map<String, String> cambios = new LinkedHashMap<>();
        if (nombre != null && !nombre.isBlank()) {
            String nuevo = nombre.trim();
            if (!nuevo.equals(paciente.getNombre())) {
                cambios.put("nombre", nuevo);
                paciente.setNombre(nuevo);
            }
        }
        if (curp != null && !curp.isBlank()) {
            String c = CurpUtil.normalizar(curp);
            CurpUtil.validar(c).ifPresent(motivo -> {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, motivo);
            });
            repository.findByCurp(c).ifPresent(otro -> {
                if (!otro.getId().equals(id)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Ya existe otro paciente con la CURP " + c);
                }
            });
            if (!c.equals(paciente.getCurp())) {
                cambios.put("curp", c);
                paciente.setCurp(c);
            }
        }
        if (telefono != null) {
            if (telefono.isBlank()) {
                // "" = borrar el teléfono (queda en null). null = no tocarlo.
                if (paciente.getTelefono() != null) {
                    cambios.put("telefono", "");
                    paciente.setTelefono(null);
                }
            } else {
                String tel = TelefonoUtil.normalizar(telefono);
                if (tel == null || tel.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El teléfono es obligatorio");
                }
                repository.findByTelefono(tel).ifPresent(otro -> {
                    if (!otro.getId().equals(id)) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "Ya existe otro paciente con el teléfono " + tel);
                    }
                });
                if (!tel.equals(paciente.getTelefono())) {
                    cambios.put("telefono", tel);
                    paciente.setTelefono(tel);
                }
            }
        }
        if (email != null) {
            String limpio = email.trim();
            String nuevo = limpio.isEmpty() ? null : limpio;
            if (nuevo != null && !nuevo.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El correo electrónico no tiene un formato válido");
            }
            if ((nuevo == null) != (paciente.getEmail() == null)
                    || (nuevo != null && !nuevo.equals(paciente.getEmail()))) {
                cambios.put("email", nuevo == null ? "" : nuevo);
                paciente.setEmail(nuevo);
            }
        }
        if (fechaNacimiento != null) {
            if (!fechaNacimiento.equals(paciente.getFechaNacimiento())) {
                cambios.put("fechaNacimiento", fechaNacimiento.toString());
                paciente.setFechaNacimiento(fechaNacimiento);
            }
        }
        paciente = repository.save(paciente);
        if (!cambios.isEmpty()) {
            UUID tramiteParaEvento = tramiteId;
            if (tramiteParaEvento == null) {
                tramiteParaEvento = tramites.findByPacienteIdOrderByCreatedAtDesc(id).stream()
                        .findFirst().map(Tramite::getId).orElse(null);
            }
            if (tramiteParaEvento != null) {
                eventos.registrar(tramiteParaEvento, TipoEvento.PACIENTE_ACTUALIZADO, "CALL_CENTER", "ASESOR",
                        json(cambios));
            }
        }
        return paciente;
    }

    /** Sobrecarga sin CURP (no la toca). */
    public Paciente actualizar(UUID id, String nombre, String telefono, String email,
                               LocalDate fechaNacimiento, UUID tramiteId) {
        return actualizar(id, nombre, telefono, email, fechaNacimiento, tramiteId, null);
    }

    /**
     * Restablece la contraseña de un paciente. Las contraseñas son
     * PBKDF2 y NO se pueden recuperar ni mostrar: aquí solo se pone
     * una nueva (nunca se devuelve el hash ni la contraseña actual).
     * Si `password` viene vacía o no viene, se genera una aleatoria
     * legible de 12 caracteres (letras+números, sin caracteres
     * ambiguos: sin 0/O, 1/I/l); si viene, mínimo 6 caracteres (400 si
     * no). La nueva contraseña se guarda con hash PBKDF2 y se devuelve
     * UNA VEZ (el controlador la pone en la respuesta; el log de
     * eventos solo registra que se restableció, jamás la contraseña).
     * El cambio queda en el log del trámite más reciente del paciente,
     * si tiene alguno; si no tiene trámite, solo se guarda el cambio.
     */
    public String restablecerPassword(UUID id, String password) {
        Paciente paciente = buscarPorId(id);
        String nueva = (password == null || password.isBlank())
                ? generarPasswordLegible(12) : password;
        if (nueva.length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La contraseña debe tener al menos 6 caracteres");
        }
        paciente.setPasswordHash(passwords.hash(nueva));
        repository.save(paciente);
        tramites.findByPacienteIdOrderByCreatedAtDesc(id).stream()
                .findFirst()
                .ifPresent(t -> eventos.registrar(t.getId(), TipoEvento.PASSWORD_RESTABLECIDA,
                        "CALL_CENTER", "ASESOR",
                        json(Map.of("pacienteId", id.toString()))));
        return nueva;
    }

    /** Contraseña aleatoria legible: sin 0/O, 1/I/l (no se confunden al dictarla). */
    private static String generarPasswordLegible(int longitud) {
        final String alfabeto = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        SecureRandom azar = new SecureRandom();
        StringBuilder sb = new StringBuilder(longitud);
        for (int i = 0; i < longitud; i++) {
            sb.append(alfabeto.charAt(azar.nextInt(alfabeto.length())));
        }
        return sb.toString();
    }

    private String json(Map<String, String> mapa) {
        try {
            return objectMapper.writeValueAsString(mapa);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo serializar el payload a JSON");
        }
    }
}
