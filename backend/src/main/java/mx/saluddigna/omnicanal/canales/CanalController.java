package mx.saluddigna.omnicanal.canales;

import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import mx.saluddigna.omnicanal.eventos.EventoService;
import mx.saluddigna.omnicanal.eventos.domain.TipoEvento;
import mx.saluddigna.omnicanal.identidad.PacienteService;
import mx.saluddigna.omnicanal.identidad.domain.Paciente;
import mx.saluddigna.omnicanal.tramite.TramiteService;
import mx.saluddigna.omnicanal.tramite.domain.Tramite;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Adaptadores de canal. En el hackaton son endpoints simulados:
 * WhatsApp y Call Center no se conectan a proveedores reales, pero
 * comparten el mismo tramite y el mismo log de eventos que la Web/App.
 */
@RestController
@RequestMapping("/api/canales")
public class CanalController {

    private final PacienteService pacientes;
    private final TramiteService tramites;
    private final EventoService eventos;

    public CanalController(PacienteService pacientes, TramiteService tramites, EventoService eventos) {
        this.pacientes = pacientes;
        this.tramites = tramites;
        this.eventos = eventos;
    }

    public record MensajeWhatsappRequest(@NotBlank String telefono, @NotBlank String mensaje) {
    }

    public record NotaCallCenterRequest(UUID tramiteId, String nota) {
    }

    /**
     * Mensaje entrante de WhatsApp (simulado).
     * Resuelve al paciente por telefono, recupera su tramite activo
     * (cualquier estado salvo ATENDIDO) o crea uno nuevo, y responde
     * con el estado actual y el siguiente paso en lenguaje humano.
     */
    @PostMapping("/whatsapp/mensaje")
    public Map<String, Object> mensajeWhatsapp(@RequestBody MensajeWhatsappRequest req) {
        Optional<Paciente> pacienteOpt = pacientes.buscarPorTelefono(req.telefono());
        if (pacienteOpt.isEmpty()) {
            Map<String, Object> respuesta = new LinkedHashMap<>();
            respuesta.put("tramite", null);
            respuesta.put("estado", null);
            respuesta.put("siguientePaso", "REGISTRAR_PACIENTE");
            respuesta.put("mensaje", "Hola, soy Salud Digna. No te tengo registrado con este numero. "
                    + "Para ayudarte, dime tu nombre completo y tu fecha de nacimiento (AAAA-MM-DD).");
            return respuesta;
        }
        Paciente paciente = pacienteOpt.get();
        Optional<Tramite> activo = tramites.tramiteActivoDe(paciente.getId());
        Tramite tramite;
        boolean creado = false;
        if (activo.isEmpty()) {
            tramite = tramites.crearTramite(paciente.getId(), null, "WHATSAPP");
            creado = true;
        } else {
            tramite = activo.get();
        }
        eventos.registrar(tramite.getId(), TipoEvento.CANAL_CAMBIADO, "WHATSAPP", "PACIENTE",
                "{\"mensaje\":\"" + escapar(req.mensaje()) + "\",\"canalAnterior\":\"" + tramite.getCanalOrigen() + "\"}");

        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("tramite", tramite);
        respuesta.put("estado", tramite.getEstado().name());
        respuesta.put("siguientePaso", TramiteService.siguientePaso(tramite.getEstado()));
        respuesta.put("mensaje", mensajeHumano(paciente, tramite, creado));
        return respuesta;
    }

    private String mensajeHumano(Paciente paciente, Tramite tramite, boolean creado) {
        String nombre = paciente.getNombre().split(" ")[0];
        return switch (tramite.getEstado()) {
            case INICIADO -> creado
                    ? "Hola " + nombre + ", te cree el tramite " + tramite.getFolio() + ". Dime que estudio necesitas y te cotizo al momento."
                    : "Hola " + nombre + ", retomamos tu tramite " + tramite.getFolio() + " justo donde lo dejaste. Dime que estudio necesitas y te cotizo.";
            case DATOS_PENDIENTES -> "Hola " + nombre + ", tu tramite " + tramite.getFolio()
                    + " necesita unos datos para continuar. Dime el dato que te falta y lo registramos.";
            case COTIZADO -> "Hola " + nombre + ", tu tramite " + tramite.getFolio()
                    + " ya esta cotizado en $" + tramite.getPrecioCotizado() + ". Elige la clinica que te convenga y seguimos.";
            case CLINICA_SELECCIONADA -> "Hola " + nombre + ", ya tienes clinica en tu tramite " + tramite.getFolio()
                    + ". Dime que dia y hora prefieres para tu cita.";
            case CONFIRMADO -> "Hola " + nombre + ", tu cita del tramite " + tramite.getFolio()
                    + " esta confirmada para el " + tramite.getFechaCita() + ". Te esperamos.";
            case ATENDIDO -> "Hola " + nombre + ", tu tramite " + tramite.getFolio() + " ya fue atendido. Gracias por confiar en Salud Digna.";
        };
    }

    private String escapar(String texto) {
        return texto == null ? "" : texto.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Nota de un asesor de Call Center sobre un tramite: queda en el log como evento. */
    @PostMapping("/call-center/nota")
    public Map<String, Object> notaCallCenter(@RequestBody NotaCallCenterRequest req) {
        if (req.tramiteId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tramiteId es obligatorio");
        }
        Tramite tramite = tramites.buscar(req.tramiteId());
        eventos.registrar(tramite.getId(), TipoEvento.CANAL_CAMBIADO, "CALL_CENTER", "ASESOR",
                "{\"nota\":\"" + escapar(req.nota()) + "\"}");
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("tramiteId", tramite.getId());
        respuesta.put("folio", tramite.getFolio());
        respuesta.put("estado", tramite.getEstado().name());
        respuesta.put("siguientePaso", TramiteService.siguientePaso(tramite.getEstado()));
        return respuesta;
    }
}
