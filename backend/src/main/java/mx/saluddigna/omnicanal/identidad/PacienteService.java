package mx.saluddigna.omnicanal.identidad;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import mx.saluddigna.omnicanal.eventos.EventoService;
import mx.saluddigna.omnicanal.eventos.domain.TipoEvento;
import mx.saluddigna.omnicanal.identidad.domain.Paciente;
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

    public PacienteService(PacienteRepository repository, EventoService eventos,
                           TramiteRepository tramites, ObjectMapper objectMapper) {
        this.repository = repository;
        this.eventos = eventos;
        this.tramites = tramites;
        this.objectMapper = objectMapper;
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

    private String json(Map<String, String> mapa) {
        try {
            return objectMapper.writeValueAsString(mapa);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo serializar el payload a JSON");
        }
    }
}
