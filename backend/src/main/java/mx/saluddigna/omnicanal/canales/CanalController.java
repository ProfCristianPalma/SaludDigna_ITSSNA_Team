package mx.saluddigna.omnicanal.canales;

import jakarta.validation.constraints.NotBlank;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mx.saluddigna.omnicanal.catalogo.ClinicaRepository;
import mx.saluddigna.omnicanal.catalogo.EstudioRepository;
import mx.saluddigna.omnicanal.catalogo.domain.Clinica;
import mx.saluddigna.omnicanal.catalogo.domain.Estudio;
import mx.saluddigna.omnicanal.eventos.EventoService;
import mx.saluddigna.omnicanal.eventos.domain.TipoEvento;
import mx.saluddigna.omnicanal.identidad.PacienteService;
import mx.saluddigna.omnicanal.identidad.domain.Paciente;
import mx.saluddigna.omnicanal.tramite.TramiteService;
import mx.saluddigna.omnicanal.tramite.domain.EstadoTramite;
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
 *
 * El bot de WhatsApp es una maquina de estados guiada por texto:
 * cada mensaje se interpreta segun el estado actual del tramite
 * (COTIZADO -> elegir clinica con 1/2, CLINICA_SELECCIONADA ->
 * confirmar con una fecha AAAA-MM-DD). Antes de este arreglo el
 * endpoint solo devolvia un mensaje segun el estado, sin leer la
 * intencion del usuario, asi que un "si" repetia el mismo texto
 * una y otra vez.
 */
@RestController
@RequestMapping("/api/canales")
public class CanalController {

    private final PacienteService pacientes;
    private final TramiteService tramites;
    private final EventoService eventos;
    private final ClinicaRepository clinicas;
    private final EstudioRepository estudios;

    public CanalController(PacienteService pacientes, TramiteService tramites,
                           EventoService eventos, ClinicaRepository clinicas,
                           EstudioRepository estudios) {
        this.pacientes = pacientes;
        this.tramites = tramites;
        this.eventos = eventos;
        this.clinicas = clinicas;
        this.estudios = estudios;
    }

    public record MensajeWhatsappRequest(@NotBlank String telefono, @NotBlank String mensaje) {
    }

    public record NotaCallCenterRequest(UUID tramiteId, String nota) {
    }

    private static final Pattern FECHA_ISO = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");

    private static final Pattern FOLIO_PATTERN = Pattern.compile("SD-\\d{4}-\\d{4}", Pattern.CASE_INSENSITIVE);

    /**
     * Seleccion de un folio de la lista por su numero ("folio 2", "opcion 1",
     * "numero 3", "el 2"). Un numero suelto ("1", "2") NO se usa para esto:
     * en un tramite COTIZADO "1" significa "clinica 1", asi que seria ambiguo.
     */
    private static final Pattern SELECCION_PATTERN =
            Pattern.compile("(?:folio|opci[oó]n|n[uú]mero|el|la)\\s*(\\d{1,2})", Pattern.CASE_INSENSITIVE);

    @PostMapping("/whatsapp/mensaje")
    public Map<String, Object> mensajeWhatsapp(@RequestBody MensajeWhatsappRequest req) {
        Optional<Paciente> pacienteOpt = pacientes.buscarPorTelefono(req.telefono());
        if (pacienteOpt.isEmpty()) {
            Map<String, Object> respuesta = new LinkedHashMap<>();
            respuesta.put("tramite", null);
            respuesta.put("estado", null);
            respuesta.put("siguientePaso", "REGISTRAR_PACIENTE");
            String msg = "Hola, soy Salud Digna. No te tengo registrado con este numero. "
                    + "Para ayudarte, dime tu nombre completo y tu fecha de nacimiento (AAAA-MM-DD).";
            respuesta.put("mensaje", msg);
            respuesta.put("respuesta", msg);
            return respuesta;
        }
        Paciente paciente = pacienteOpt.get();
        // Un paciente puede tener varios folios activos, uno por estudio.
        List<Tramite> activos = tramites.listarActivosDe(paciente.getId());

        Tramite tramite;
        boolean creado = false;
        Matcher folioMatcher = FOLIO_PATTERN.matcher(req.mensaje() == null ? "" : req.mensaje());
        if (folioMatcher.find()) {
            // El mensaje trae un folio explicito: se usa ese tramite, no otro.
            String folio = folioMatcher.group().toUpperCase();
            Tramite porFolio;
            try {
                porFolio = tramites.buscarPorFolio(folio);
            } catch (ResponseStatusException e) {
                return respuestaSinTramite("No encontre ningun tramite con el folio " + folio
                        + ". Revisa el folio o dime tu estudio para empezar uno nuevo.");
            }
            if (!porFolio.getPaciente().getId().equals(paciente.getId())) {
                return respuestaSinTramite("El folio " + folio + " no pertenece a este numero de telefono.");
            }
            tramite = porFolio;
        } else {
            Optional<Tramite> seleccion = elegirDeLista(req.mensaje(), activos);
            if (seleccion.isPresent()) {
                tramite = seleccion.get();
            } else if (activos.isEmpty()) {
                tramite = tramites.crearTramite(paciente.getId(), null, "WHATSAPP");
                creado = true;
            } else if (activos.size() == 1) {
                tramite = activos.get(0);
            } else {
                // Varios folios activos y el mensaje no dice cual: no se elige
                // uno al azar, se listan y se pide el folio.
                Map<String, Object> respuesta = new LinkedHashMap<>();
                respuesta.put("tramite", null);
                respuesta.put("estado", null);
                respuesta.put("siguientePaso", "ELEGIR_FOLIO");
                respuesta.put("tramites", activos);
                String msg = listarFolios(paciente, activos);
                respuesta.put("mensaje", msg);
                respuesta.put("respuesta", msg);
                return respuesta;
            }
        }
        eventos.registrar(tramite.getId(), TipoEvento.CANAL_CAMBIADO, "WHATSAPP", "PACIENTE",
                "{\"mensaje\":\"" + escapar(req.mensaje()) + "\",\"canalAnterior\":\"" + tramite.getCanalOrigen() + "\"}");

        // Interpreta la intencion segun el estado actual. Puede avanzar el tramite.
        String textoRespuesta = procesarIntencion(paciente, tramite, req.mensaje());
        // Releer por si procesarIntencion cambio el estado.
        tramite = tramites.buscar(tramite.getId());

        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("tramite", tramite);
        respuesta.put("estado", tramite.getEstado().name());
        respuesta.put("siguientePaso", TramiteService.siguientePaso(tramite.getEstado()));
        respuesta.put("mensaje", textoRespuesta);
        respuesta.put("respuesta", textoRespuesta);
        if (tramite.getEstado() == EstadoTramite.COTIZADO) {
            respuesta.put("clinicas", clinicas.findAll());
        }
        return respuesta;
    }

    private Map<String, Object> respuestaSinTramite(String mensaje) {
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("tramite", null);
        respuesta.put("estado", null);
        respuesta.put("siguientePaso", "ELEGIR_FOLIO");
        respuesta.put("mensaje", mensaje);
        respuesta.put("respuesta", mensaje);
        return respuesta;
    }

    private Optional<Tramite> elegirDeLista(String mensaje, List<Tramite> activos) {
        if (mensaje == null || activos.isEmpty()) return Optional.empty();
        Matcher m = SELECCION_PATTERN.matcher(mensaje);
        if (!m.find()) return Optional.empty();
        int n;
        try {
            n = Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (n < 1 || n > activos.size()) return Optional.empty();
        return Optional.of(activos.get(n - 1));
    }

    private String listarFolios(Paciente paciente, List<Tramite> activos) {
        String nombre = paciente.getNombre().split(" ")[0];
        StringBuilder sb = new StringBuilder();
        sb.append("Hola ").append(nombre).append(", tienes ").append(activos.size())
                .append(" tramites activos. ¿Con cual seguimos?\n");
        for (int i = 0; i < activos.size(); i++) {
            Tramite t = activos.get(i);
            String estudio = t.getEstudioId() == null ? "sin estudio aun"
                    : estudios.findById(t.getEstudioId()).map(Estudio::getNombre).orElse("estudio");
            sb.append(i + 1).append(") ").append(t.getFolio()).append(" — ").append(estudio)
                    .append(" (").append(t.getEstado().name()).append(")\n");
        }
        sb.append("Responde con el folio (por ejemplo ").append(activos.get(0).getFolio())
                .append(") o con 'folio 1', 'folio 2', etc.");
        return sb.toString();
    }

    private String procesarIntencion(Paciente paciente, Tramite tramite, String mensajeCrudo) {
        String nombre = paciente.getNombre().split(" ")[0];
        String norm = normalizar(mensajeCrudo);

        if (tramite.getEstado() == EstadoTramite.COTIZADO) {
            Optional<Clinica> elegida = elegirClinica(norm);
            if (elegida.isPresent()) {
                Tramite actualizado = tramites.seleccionarClinica(tramite.getId(), elegida.get().getId());
                return "Listo " + nombre + ", elegiste " + elegida.get().getNombre()
                        + " para tu tramite " + actualizado.getFolio()
                        + ". Ahora dime que dia prefieres para tu cita, en formato AAAA-MM-DD (por ejemplo 2026-11-24).";
            }
            if (norm.contains("si") || norm.contains("quiero") || norm.contains("elegir")
                    || norm.contains("clinica") || norm.contains("continuar") || norm.contains("adelante")) {
                List<Clinica> lista = clinicas.findAll();
                StringBuilder sb = new StringBuilder();
                sb.append("Perfecto ").append(nombre).append(", continuamos tu tramite ")
                        .append(tramite.getFolio())
                        .append(". Elige una clinica respondiendo con el numero:\n");
                for (int i = 0; i < lista.size(); i++) {
                    sb.append(i + 1).append(") ").append(lista.get(i).getNombre())
                            .append(" (").append(lista.get(i).getCiudad()).append(")\n");
                }
                sb.append("Responde 1 o 2, o escribe el nombre de la clinica.");
                return sb.toString();
            }
            return mensajeHumano(paciente, tramite, false);
        }

        if (tramite.getEstado() == EstadoTramite.CLINICA_SELECCIONADA) {
            Matcher m = FECHA_ISO.matcher(mensajeCrudo == null ? "" : mensajeCrudo);
            if (m.find()) {
                LocalDate fecha = LocalDate.of(Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
                Tramite actualizado = tramites.confirmar(tramite.getId(), LocalDateTime.of(fecha, java.time.LocalTime.of(10, 0)));
                return "Confirmado " + nombre + ". Tu cita del tramite " + actualizado.getFolio()
                        + " quedo para el " + fecha + " a las 10:00. Te esperamos en la clinica.";
            }
            return "Ya tienes clinica en tu tramite " + tramite.getFolio()
                    + ". Dime que dia prefieres para tu cita, en formato AAAA-MM-DD (por ejemplo 2026-11-24).";
        }

        if (tramite.getEstado() == EstadoTramite.INICIADO || tramite.getEstado() == EstadoTramite.DATOS_PENDIENTES) {
            Optional<Estudio> estudio = elegirEstudio(norm);
            if (estudio.isPresent()) {
                Tramite actualizado = tramites.cotizar(tramite.getId(), estudio.get().getId());
                return "Hola " + nombre + ", tu tramite " + actualizado.getFolio()
                        + " ya esta cotizado: " + estudio.get().getNombre()
                        + " por $" + actualizado.getPrecioCotizado()
                        + ". Responde SI para elegir clinica.";
            }
            return mensajeHumano(paciente, tramite, false);
        }

        return mensajeHumano(paciente, tramite, false);
    }

    private Optional<Clinica> elegirClinica(String norm) {
        List<Clinica> lista = clinicas.findAll();
        if (lista.isEmpty()) return Optional.empty();
        String token = norm.trim();
        if (token.equals("1") || norm.contains("puebla") || norm.contains("centro")) {
            return lista.stream().filter(c -> normalizar(c.getNombre()).contains("puebla")).findFirst()
                    .or(() -> Optional.of(lista.get(0)));
        }
        if (token.equals("2") || norm.contains("tehuacan")) {
            return lista.stream().filter(c -> normalizar(c.getNombre()).contains("tehuacan")).findFirst()
                    .or(() -> lista.size() > 1 ? Optional.of(lista.get(1)) : Optional.of(lista.get(0)));
        }
        for (Clinica c : lista) {
            if (norm.contains(normalizar(c.getNombre())) || normalizar(c.getNombre()).contains(norm) && norm.length() > 4) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    private Optional<Estudio> elegirEstudio(String norm) {
        for (Estudio e : estudios.findAll()) {
            String n = normalizar(e.getNombre());
            if (norm.contains("ultrasonido") && n.contains("ultrasonido")) return Optional.of(e);
            if (norm.contains("tiroideo") && n.contains("tiroideo")) return Optional.of(e);
            if (norm.contains("radiografia") && n.contains("radiografia")) return Optional.of(e);
            if ((norm.contains("analisis") || norm.contains("sangre")) && n.contains("analisis")) return Optional.of(e);
        }
        return Optional.empty();
    }

    private String normalizar(String s) {
        if (s == null) return "";
        String sinAcentos = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinAcentos.toLowerCase().trim();
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
