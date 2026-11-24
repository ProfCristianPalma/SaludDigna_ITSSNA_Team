package mx.saluddigna.omnicanal.canales;

import jakarta.validation.constraints.NotBlank;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
 * El bot de WhatsApp es un chatbot con contexto e intenciones:
 * - Recuerda el folio en contexto (el front manda el campo `folio`
 *   con cada mensaje), así que "1" o "25/11/2026" se aplican al
 *   trámite que se venía siguiendo, sin rebotar a la lista de folios.
 * - Entiende la intención según el estado (elegir clínica con 1/2,
 *   nombre o ciudad; fecha en DD/MM/AAAA, "hoy", "mañana" o
 *   "pasado mañana"; estudio por nombre o palabra clave; "ayuda").
 * - Cuando no entiende, lo dice claro ("No entendí ..."), muestra
 *   el estado del folio y las opciones válidas ahora, con ejemplos.
 *   Nunca inventa un avance ni confirma algo que no pasó.
 * - Un "hola" o "ayuda" NO crea trámites: solo se crea uno cuando el
 *   usuario pide un estudio concreto. Un teléfono no registrado se
 *   registra con nombre + fecha de nacimiento en el mismo chat.
 *
 * Fechas: el usuario escribe en formato latinoamericano DD/MM/AAAA
 * (se acepta tambien AAAA-MM-DD por compatibilidad) y las citas se
 * le muestran en DD/MM/AAAA. Por dentro se sigue guardando LocalDate
 * en ISO; la conversion solo existe aqui, en la presentacion.
 *
 * Contrato de respuesta: además de `mensaje`/`tramite`/`estado`/
 * `siguientePaso`, cada respuesta trae `opciones` (respuestas
 * sugeridas para ese estado, que el front muestra como botones) y
 * `entendido` (si el bot entendió la intención o no).
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

    /**
     * Mensaje de WhatsApp. `folio` es opcional: es el folio en contexto
     * que manda el front con cada mensaje (el que el usuario venía
     * siguiendo), para no tener que adivinarlo entre varios folios.
     */
    public record MensajeWhatsappRequest(@NotBlank String telefono, @NotBlank String mensaje, String folio) {
    }

    public record NotaCallCenterRequest(UUID tramiteId, String nota) {
    }

    /** Lo que el bot entendió (o no) y qué responde. `tramite` es el trámite tras aplicar la intención, si cambió. */
    private record ResultadoBot(String texto, boolean entendido, List<String> opciones, Tramite tramite) {
        ResultadoBot(String texto, boolean entendido, List<String> opciones) {
            this(texto, entendido, opciones, null);
        }
    }

    private static final Pattern FECHA_ISO = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");

    /** Fecha latinoamericana DD/MM/AAAA (dia/mes/ano, no mes/dia). */
    private static final Pattern FECHA_LATINA = Pattern.compile("(\\d{1,2})/(\\d{1,2})/(\\d{4})");

    /** Formato en el que se le muestran las fechas al usuario: DD/MM/AAAA. */
    private static final DateTimeFormatter FORMATO_LATINO = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final DateTimeFormatter FORMATO_LATINO_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** Mañana en DD/MM/AAAA (fecha sugerida para confirmar la cita). */
    private static String manana() {
        return LocalDate.now().plusDays(1).format(FORMATO_LATINO);
    }

    /**
     * Extrae una fecha del mensaje del usuario. Acepta DD/MM/AAAA
     * (formato latinoamericano), AAAA-MM-DD (compatibilidad) y las
     * palabras "hoy", "mañana" y "pasado mañana". Devuelve vacío si
     * no hay fecha o si no es una fecha real (31/02/2026).
     */
    static Optional<LocalDate> extraerFecha(String mensaje) {
        Optional<LocalDate> explicita = extraerFechaExplicita(mensaje);
        if (explicita.isPresent()) return explicita;
        if (mensaje == null) return Optional.empty();
        String norm = normalizar(mensaje);
        if (Pattern.compile("\\bpasado manana\\b").matcher(norm).find()) {
            return Optional.of(LocalDate.now().plusDays(2));
        }
        if (Pattern.compile("\\bmanana\\b").matcher(norm).find()) {
            return Optional.of(LocalDate.now().plusDays(1));
        }
        if (Pattern.compile("\\bhoy\\b").matcher(norm).find()) {
            return Optional.of(LocalDate.now());
        }
        return Optional.empty();
    }

    /** Solo fechas escritas (DD/MM/AAAA o AAAA-MM-DD), sin "hoy"/"mañana": para registrar un nacimiento. */
    static Optional<LocalDate> extraerFechaExplicita(String mensaje) {
        Matcher m = buscarFechaExplicita(mensaje);
        if (m == null) return Optional.empty();
        try {
            if (m.pattern() == FECHA_LATINA) {
                return Optional.of(LocalDate.of(Integer.parseInt(m.group(3)),
                        Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1))));
            }
            return Optional.of(LocalDate.of(Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))));
        } catch (java.time.DateTimeException e) {
            return Optional.empty();
        }
    }

    /** El primer trozo del mensaje que parece una fecha escrita (aunque sea imposible, como 31/02/2026). */
    private static Matcher buscarFechaExplicita(String mensaje) {
        if (mensaje == null) return null;
        Matcher latina = FECHA_LATINA.matcher(mensaje);
        if (latina.find()) return latina;
        Matcher iso = FECHA_ISO.matcher(mensaje);
        if (iso.find()) return iso;
        return null;
    }

    /** ¿El mensaje trae algo que parece una fecha escrita? (sirve para distinguir "fecha imposible" de "eso no era una fecha"). */
    private static boolean pareceFecha(String mensaje) {
        return buscarFechaExplicita(mensaje) != null;
    }

    private static final Pattern FOLIO_PATTERN = Pattern.compile("SD-\\d{4}-\\d{4}", Pattern.CASE_INSENSITIVE);

    /**
     * Seleccion de un folio de la lista por su numero ("folio 2", "opcion 1",
     * "numero 3"). Un numero suelto ("1", "2") o "la 1" NO se usa para esto:
     * en un tramite COTIZADO "1" significa "clinica 1", asi que seria ambiguo.
     */
    private static final Pattern SELECCION_PATTERN =
            Pattern.compile("(?:folio|opci[oó]n|n[uú]mero)\\s*(\\d{1,2})", Pattern.CASE_INSENSITIVE);

    @PostMapping("/whatsapp/mensaje")
    public Map<String, Object> mensajeWhatsapp(@RequestBody MensajeWhatsappRequest req) {
        // El telefono se normaliza a 10 digitos (ver TelefonoUtil): un mismo
        // paciente no debe duplicarse por escribir +52 en un canal y no en otro.
        String telefono = mx.saluddigna.omnicanal.identidad.TelefonoUtil.normalizar(req.telefono());
        String texto = req.mensaje() == null ? "" : req.mensaje().trim();
        String norm = normalizar(texto);
        Optional<Paciente> pacienteOpt = pacientes.buscarPorTelefono(telefono);
        if (pacienteOpt.isEmpty()) {
            return responderRegistro(telefono, texto, norm);
        }
        Paciente paciente = pacienteOpt.get();
        // Un paciente puede tener varios folios activos, uno por estudio.
        List<Tramite> activos = tramites.listarActivosDe(paciente.getId());

        // 1) Folio explícito en el texto, o en contexto (campo `folio`
        // del front). El del texto manda si trae uno distinto al contexto.
        String folioPedido = null;
        Matcher folioEnTexto = FOLIO_PATTERN.matcher(texto);
        if (folioEnTexto.find()) {
            folioPedido = folioEnTexto.group().toUpperCase();
        } else if (req.folio() != null && !req.folio().isBlank()) {
            folioPedido = req.folio().trim().toUpperCase();
        }

        Tramite tramite = null;
        if (folioPedido != null) {
            Tramite porFolio;
            try {
                porFolio = tramites.buscarPorFolio(folioPedido);
            } catch (ResponseStatusException e) {
                return respuestaSinTramite("No encontré ningún trámite con el folio " + folioPedido
                        + ". Revisa el folio o dime tu estudio para empezar uno nuevo.", "ELEGIR_FOLIO", false, List.of());
            }
            if (!porFolio.getPaciente().getId().equals(paciente.getId())) {
                return respuestaSinTramite("El folio " + folioPedido + " no pertenece a este número de teléfono.",
                        "ELEGIR_FOLIO", false, List.of());
            }
            tramite = porFolio;
        } else {
            // 2) "folio 1", "opción 2", "número 3".
            Optional<Tramite> seleccion = elegirDeLista(texto, activos);
            if (seleccion.isPresent()) {
                tramite = seleccion.get();
            } else {
                // 3) La intención identifica el folio por su estado
                // (p. ej. "1" -> el folio COTIZADO; una fecha -> el
                // folio CLINICA_SELECCIONADA; un estudio -> el INICIADO).
                Optional<Tramite> porIntencion = porIntencion(norm, texto, activos);
                if (porIntencion.isPresent()) {
                    tramite = porIntencion.get();
                } else if (activos.size() == 1) {
                    // 4) Un solo folio activo: es ese.
                    tramite = activos.get(0);
                } else if (activos.isEmpty()) {
                    // 5) Sin folios: solo se crea un trámite si el usuario
                    // pide un estudio concreto; un saludo no crea nada.
                    return responderSinFolios(paciente, texto, norm);
                } else {
                    // 6) Varios folios y no se puede saber con cuál seguir:
                    // no se elige uno al azar, se listan y se pregunta.
                    return respuestaListaFolios(paciente, activos, texto, norm);
                }
            }
        }

        eventos.registrar(tramite.getId(), TipoEvento.CANAL_CAMBIADO, "WHATSAPP", "PACIENTE",
                "{\"mensaje\":\"" + escapar(texto) + "\",\"canalAnterior\":\"" + tramite.getCanalOrigen() + "\"}");

        // Interpreta la intencion segun el estado actual. Puede avanzar el tramite o crear uno nuevo.
        ResultadoBot resultado = procesarIntencion(paciente, tramite, texto);
        Tramite actualizado = resultado.tramite() != null ? resultado.tramite() : tramites.buscar(tramite.getId());
        // La lista de folios se relee: el estado del folio pudo cambiar con este mensaje.
        List<Tramite> activosActuales = tramites.listarActivosDe(paciente.getId());
        return respuestaConTramite(actualizado, activosActuales, resultado);
    }

    /**
     * Teléfono no registrado: el bot guía el registro en el mismo chat.
     * Si el mensaje trae nombre completo + fecha de nacimiento en
     * DD/MM/AAAA, crea al paciente en la BD (PacienteService.registrar)
     * y lo confirma; si falta algo, dice exactamente qué falta. Un
     * saludo no pide datos a ciegas: explica cómo empezar.
     */
    private Map<String, Object> responderRegistro(String telefono, String texto, String norm) {
        if (esSaludoOAyuda(norm)) {
            return respuestaSinTramite("Hola, soy Salud Digna. No te tengo registrado con este número (" + telefono + "). "
                    + "Para empezar, dime tu nombre completo y tu fecha de nacimiento en formato DD/MM/AAAA "
                    + "(por ejemplo: Ana García López 14/05/1990), o crea tu cuenta en la Web.",
                    "REGISTRAR_PACIENTE", true, List.of());
        }
        Matcher fechaMatcher = buscarFechaExplicita(texto);
        Optional<LocalDate> fechaOpt = extraerFechaExplicita(texto);
        if (fechaMatcher != null && fechaOpt.isEmpty()) {
            return respuestaSinTramite("Esa fecha no existe o no la pude leer. Escríbela en formato DD/MM/AAAA, "
                    + "por ejemplo 14/05/1990, junto con tu nombre completo.",
                    "REGISTRAR_PACIENTE", true, List.of());
        }
        String nombre = extraerNombreRegistro(texto, fechaMatcher);
        boolean nombreIncompleto = nombre.isBlank() || !nombre.contains(" ");
        if (fechaOpt.isEmpty()) {
            String falta = nombreIncompleto
                    ? "Me falta tu nombre completo y tu fecha de nacimiento."
                    : "Me falta tu fecha de nacimiento.";
            return respuestaSinTramite(falta + " Escríbelos en un mensaje, por ejemplo: "
                    + (nombreIncompleto ? "Ana García López" : nombre) + " 14/05/1990. La fecha va en formato DD/MM/AAAA.",
                    "REGISTRAR_PACIENTE", true, List.of());
        }
        LocalDate fecha = fechaOpt.get();
        if (fecha.isAfter(LocalDate.now()) || fecha.getYear() < 1900) {
            return respuestaSinTramite("Esa fecha no puede ser tu fecha de nacimiento (" + fecha.format(FORMATO_LATINO) + "). "
                    + "Revisa el día, el mes y el año.",
                    "REGISTRAR_PACIENTE", true, List.of());
        }
        if (nombreIncompleto) {
            return respuestaSinTramite("Me falta tu nombre completo (nombre y apellidos). Escríbelo junto con tu fecha de nacimiento, "
                    + "por ejemplo: Ana García López " + fecha.format(FORMATO_LATINO) + ".",
                    "REGISTRAR_PACIENTE", true, List.of());
        }
        Paciente nuevo;
        try {
            nuevo = pacientes.registrar(new Paciente(nombre, telefono, null, fecha));
        } catch (ResponseStatusException e) {
            // Carrera: el teléfono se registró entre la búsqueda y el alta.
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                Optional<Paciente> existente = pacientes.buscarPorTelefono(telefono);
                if (existente.isPresent()) {
                    nuevo = existente.get();
                } else {
                    throw e;
                }
            } else {
                throw e;
            }
        }
        String primerNombre = nuevo.getNombre().split(" ")[0];
        return respuestaSinTramite("Listo " + primerNombre + ", ya quedaste registrado con este número. ¿Qué estudio necesitas?\n"
                + textoEstudios(), "ELEGIR_ESTUDIO", true, opcionesEstudios());
    }

    /** Quita la fecha del mensaje y deja el nombre; quita muletillas ("me llamo", "soy", "mi nombre es"). */
    private String extraerNombreRegistro(String texto, Matcher fechaMatcher) {
        String resto;
        if (fechaMatcher == null) {
            resto = texto;
        } else {
            resto = (texto.substring(0, fechaMatcher.start()) + " " + texto.substring(fechaMatcher.end())).trim();
        }
        resto = resto.replaceAll("(?i)^\\s*(me llamo|mi nombre es|yo soy|soy|nombre\\s*:?)\\s*", "").trim();
        resto = resto.replaceAll("[,;:.]+$", "").trim();
        return resto.replaceAll("\\s+", " ");
    }

    /**
     * Paciente sin folios activos: NO se crea un trámite por un saludo.
     * Solo se crea cuando el usuario pide un estudio concreto (y se
     * cotiza en el mismo paso). Todo lo demás es ayuda o "no entendí".
     */
    private Map<String, Object> responderSinFolios(Paciente paciente, String texto, String norm) {
        String nombre = paciente.getNombre().split(" ")[0];
        Optional<Estudio> estudio = elegirEstudio(norm);
        if (estudio.isPresent()) {
            Tramite nuevo = tramites.crearTramite(paciente.getId(), estudio.get().getId(), "WHATSAPP");
            nuevo = tramites.cotizar(nuevo.getId(), estudio.get().getId());
            eventos.registrar(nuevo.getId(), TipoEvento.CANAL_CAMBIADO, "WHATSAPP", "PACIENTE",
                    "{\"mensaje\":\"" + escapar(texto) + "\"}");
            String resp = "Hola " + nombre + ", tu trámite " + nuevo.getFolio() + " ya está cotizado: "
                    + estudio.get().getNombre() + " por $" + nuevo.getPrecioCotizado()
                    + ". Ahora elige clínica.";
            return respuestaConTramite(nuevo, List.of(), new ResultadoBot(resp, true, opcionesClinicas(), nuevo));
        }
        if (esSaludoOAyuda(norm) || quiereCotizar(norm)) {
            String resp = "Hola " + nombre + ", soy Salud Digna 👋. Aún no tienes trámites activos. Puedo cotizarte un estudio:\n"
                    + textoEstudios();
            return respuestaSinTramite(resp, "ELEGIR_ESTUDIO", true, opcionesEstudios());
        }
        String resp = "No entendí \"" + texto + "\", " + nombre + ". Aún no tienes trámites activos. Puedo cotizarte un estudio:\n"
                + textoEstudios();
        return respuestaSinTramite(resp, "ELEGIR_ESTUDIO", false, opcionesEstudios());
    }

    /** Varios folios y el mensaje no dice con cuál seguir: se listan y se pregunta, sin elegir al azar. */
    private Map<String, Object> respuestaListaFolios(Paciente paciente, List<Tramite> activos, String texto, String norm) {
        String prefacio;
        boolean entendido;
        Optional<String> ambiguo = motivoAmbiguo(norm, texto, activos);
        if (esSaludoOAyuda(norm)) {
            prefacio = "Hola " + paciente.getNombre().split(" ")[0] + ", tienes " + activos.size() + " trámites activos. ¿Con cuál seguimos?";
            entendido = true;
        } else if (ambiguo.isPresent()) {
            prefacio = "No sé a cuál de tus folios te refieres con ese mensaje: " + ambiguo.get()
                    + ". Dime el folio con el que seguimos.";
            entendido = false;
        } else {
            prefacio = "No entendí \"" + texto + "\". Tienes " + activos.size() + " trámites activos: dime con cuál seguimos.";
            entendido = false;
        }
        Map<String, Object> respuesta = respuestaSinTramite(prefacio + "\n" + listarFoliosTexto(activos),
                "ELEGIR_FOLIO", entendido, foliosComoOpciones(activos));
        respuesta.put("tramites", activos);
        return respuesta;
    }

    /** Por qué el mensaje es ambiguo entre varios folios (p. ej. dos COTIZADO y el usuario escribió "1"). */
    private Optional<String> motivoAmbiguo(String norm, String texto, List<Tramite> activos) {
        if (elegirClinica(norm).isPresent() || norm.contains("clinica")) {
            long n = activos.stream().filter(t -> t.getEstado() == EstadoTramite.COTIZADO).count();
            if (n > 1) return Optional.of("tienes " + n + " trámites en estado COTIZADO");
        }
        if (extraerFecha(texto).isPresent()) {
            long n = activos.stream().filter(t -> t.getEstado() == EstadoTramite.CLINICA_SELECCIONADA).count();
            if (n > 1) return Optional.of("tienes " + n + " trámites esperando fecha");
        }
        if (elegirEstudio(norm).isPresent()) {
            long n = activos.stream()
                    .filter(t -> t.getEstado() == EstadoTramite.INICIADO || t.getEstado() == EstadoTramite.DATOS_PENDIENTES).count();
            if (n > 1) return Optional.of("tienes " + n + " trámites sin estudio");
        }
        return Optional.empty();
    }

    /**
     * La intención identifica el folio por su estado: elegir clínica
     * -> el COTIZADO; una fecha -> el CLINICA_SELECCIONADA; un estudio
     * -> el INICIADO/DATOS_PENDIENTES. Solo si hay exactamente uno en
     * ese estado; si hay varios, no se adivina (vacío -> lista).
     */
    private Optional<Tramite> porIntencion(String norm, String texto, List<Tramite> activos) {
        // "Cambiar clínica" es del folio que ya tiene clínica, no del COTIZADO.
        if (norm.contains("cambiar clinica") || norm.contains("cambiar la clinica") || norm.contains("otra clinica")) {
            List<Tramite> c = activos.stream().filter(t -> t.getEstado() == EstadoTramite.CLINICA_SELECCIONADA).toList();
            if (c.size() == 1) return Optional.of(c.get(0));
        }
        if (elegirClinica(norm).isPresent() || norm.contains("clinica")) {
            List<Tramite> c = activos.stream().filter(t -> t.getEstado() == EstadoTramite.COTIZADO).toList();
            if (c.size() == 1) return Optional.of(c.get(0));
        }
        if (extraerFecha(texto).isPresent()) {
            List<Tramite> c = activos.stream().filter(t -> t.getEstado() == EstadoTramite.CLINICA_SELECCIONADA).toList();
            if (c.size() == 1) return Optional.of(c.get(0));
        }
        if (elegirEstudio(norm).isPresent()) {
            List<Tramite> c = activos.stream()
                    .filter(t -> t.getEstado() == EstadoTramite.INICIADO || t.getEstado() == EstadoTramite.DATOS_PENDIENTES)
                    .toList();
            if (c.size() == 1) return Optional.of(c.get(0));
        }
        return Optional.empty();
    }

    private Map<String, Object> respuestaConTramite(Tramite tramite, List<Tramite> activos, ResultadoBot resultado) {
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("tramite", tramite);
        respuesta.put("estado", tramite.getEstado().name());
        respuesta.put("siguientePaso", TramiteService.siguientePaso(tramite.getEstado()));
        respuesta.put("mensaje", resultado.texto());
        respuesta.put("respuesta", resultado.texto());
        respuesta.put("opciones", resultado.opciones());
        respuesta.put("entendido", resultado.entendido());
        respuesta.put("tramites", activos);
        if (tramite.getEstado() == EstadoTramite.COTIZADO) {
            respuesta.put("clinicas", clinicas.findAll());
        }
        return respuesta;
    }

    private Map<String, Object> respuestaSinTramite(String mensaje, String siguientePaso, boolean entendido, List<String> opciones) {
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("tramite", null);
        respuesta.put("estado", null);
        respuesta.put("siguientePaso", siguientePaso);
        respuesta.put("mensaje", mensaje);
        respuesta.put("respuesta", mensaje);
        respuesta.put("opciones", opciones);
        respuesta.put("entendido", entendido);
        respuesta.put("tramites", List.of());
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

    private String listarFoliosTexto(List<Tramite> activos) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < activos.size(); i++) {
            Tramite t = activos.get(i);
            String estudio = t.getEstudioId() == null ? "sin estudio aún"
                    : estudios.findById(t.getEstudioId()).map(Estudio::getNombre).orElse("estudio");
            sb.append(i + 1).append(") ").append(t.getFolio()).append(" — ").append(estudio)
                    .append(" (").append(t.getEstado().name()).append(")\n");
        }
        sb.append("Responde con el folio (por ejemplo ").append(activos.get(0).getFolio())
                .append(") o con 'folio 1', 'folio 2', etc.");
        return sb.toString();
    }

    private List<String> foliosComoOpciones(List<Tramite> activos) {
        return activos.stream().map(Tramite::getFolio).toList();
    }

    /**
     * Interpreta la intención según el estado actual. El orden importa:
     * primero lo que no está disponible (cancelar) y el folio como
     * mensaje, luego la acción propia del estado, luego saludo/ayuda
     * y gracias, y al final "no entendí" con las opciones del estado.
     */
    private ResultadoBot procesarIntencion(Paciente paciente, Tramite tramite, String mensajeCrudo) {
        String norm = normalizar(mensajeCrudo);
        String texto = mensajeCrudo == null ? "" : mensajeCrudo.trim();

        // Cancelar no está disponible por WhatsApp en ningún estado.
        if (norm.contains("cancelar")) {
            return new ResultadoBot("Por WhatsApp no puedo cancelar citas ni trámites, " + paciente.getNombre().split(" ")[0]
                    + ". Para cancelar, llama al Call Center y un asesor lo hace por ti con tu folio " + tramite.getFolio() + ".",
                    true, opcionesPara(tramite), tramite);
        }
        // El mensaje es solo el folio: retomar ese trámite.
        if (FOLIO_PATTERN.matcher(texto).matches()) {
            return new ResultadoBot(resumenEstado(paciente, tramite) + " ¿Seguimos con este?",
                    true, opcionesPara(tramite), tramite);
        }

        Optional<ResultadoBot> accion = switch (tramite.getEstado()) {
            case COTIZADO -> procesarCotizado(paciente, tramite, norm);
            case CLINICA_SELECCIONADA -> procesarClinicaSeleccionada(paciente, tramite, mensajeCrudo, norm);
            case INICIADO, DATOS_PENDIENTES -> procesarInicio(paciente, tramite, norm);
            case CONFIRMADO -> procesarConfirmado(paciente, tramite, norm);
            case ATENDIDO -> procesarAtendido(paciente, tramite, norm);
        };
        if (accion.isPresent()) return accion.get();
        if (esSaludoOAyuda(norm)) return ayuda(paciente, tramite);
        if (norm.contains("gracias") || norm.contains("adios") || norm.contains("chao") || norm.contains("hasta luego")) {
            return new ResultadoBot("¡De nada, " + paciente.getNombre().split(" ")[0] + "! " + resumenEstado(paciente, tramite),
                    true, opcionesPara(tramite), tramite);
        }
        return noEntendi(paciente, tramite, mensajeCrudo);
    }

    private Optional<ResultadoBot> procesarCotizado(Paciente paciente, Tramite tramite, String norm) {
        String nombre = paciente.getNombre().split(" ")[0];
        Optional<Clinica> elegida = elegirClinica(norm);
        if (elegida.isPresent()) {
            Tramite actualizado = tramites.seleccionarClinica(tramite.getId(), elegida.get().getId());
            String resp = "Listo " + nombre + ", elegiste " + elegida.get().getNombre()
                    + " para tu trámite " + actualizado.getFolio()
                    + ". Ahora dime qué día prefieres para tu cita, en formato DD/MM/AAAA (por ejemplo " + manana() + ").";
            return Optional.of(new ResultadoBot(resp, true, opcionesFechas(), actualizado));
        }
        if (norm.equals("si") || norm.contains("clinica") || norm.contains("elegir") || norm.contains("quiero")
                || norm.contains("continuar") || norm.contains("adelante")) {
            return Optional.of(new ResultadoBot(listarClinicasTexto(nombre, tramite), true, opcionesClinicas(), tramite));
        }
        return Optional.empty();
    }

    private Optional<ResultadoBot> procesarClinicaSeleccionada(Paciente paciente, Tramite tramite, String mensajeCrudo, String norm) {
        String nombre = paciente.getNombre().split(" ")[0];
        if (norm.contains("cambiar clinica") || norm.contains("cambiar la clinica") || norm.contains("otra clinica")) {
            Tramite actualizado = tramites.volverACotizado(tramite.getId());
            return Optional.of(new ResultadoBot("Sin problema, " + nombre + ". Volvemos a elegir clínica para tu trámite "
                    + actualizado.getFolio() + ":\n" + listarClinicasTexto(nombre, actualizado),
                    true, opcionesClinicas(), actualizado));
        }
        Optional<LocalDate> fechaOpt = extraerFecha(mensajeCrudo);
        if (fechaOpt.isPresent()) {
            LocalDate fecha = fechaOpt.get();
            if (fecha.isBefore(LocalDate.now())) {
                return Optional.of(new ResultadoBot("Esa fecha ya pasó (" + fecha.format(FORMATO_LATINO)
                        + " es anterior a hoy, " + LocalDate.now().format(FORMATO_LATINO) + "). Dime otro día para tu cita, en formato DD/MM/AAAA.",
                        true, opcionesFechas(), tramite));
            }
            Tramite actualizado = tramites.confirmar(tramite.getId(), LocalDateTime.of(fecha, LocalTime.of(10, 0)));
            return Optional.of(new ResultadoBot("Confirmado " + nombre + ". Tu cita del trámite " + actualizado.getFolio()
                    + " quedó para el " + fecha.format(FORMATO_LATINO) + " a las 10:00 en " + nombreClinica(actualizado) + ". Te esperamos.",
                    true, List.of("¿Cuándo es mi cita?", "Ayuda"), actualizado));
        }
        if (pareceFecha(mensajeCrudo)) {
            return Optional.of(new ResultadoBot("Esa fecha no existe o no la pude leer (" + mensajeCrudo.trim() + "). "
                    + "Escríbela en formato DD/MM/AAAA, por ejemplo " + manana() + ", o dime \"hoy\" o \"mañana\".",
                    true, opcionesFechas(), tramite));
        }
        return Optional.empty();
    }

    private Optional<ResultadoBot> procesarInicio(Paciente paciente, Tramite tramite, String norm) {
        String nombre = paciente.getNombre().split(" ")[0];
        Optional<Estudio> estudio = elegirEstudio(norm);
        if (estudio.isPresent()) {
            Tramite actualizado = tramites.cotizar(tramite.getId(), estudio.get().getId());
            return Optional.of(new ResultadoBot("Hola " + nombre + ", tu trámite " + actualizado.getFolio()
                    + " ya está cotizado: " + estudio.get().getNombre()
                    + " por $" + actualizado.getPrecioCotizado()
                    + ". Ahora elige clínica.", true, opcionesClinicas(), actualizado));
        }
        if (quiereCotizar(norm)) {
            return Optional.of(new ResultadoBot("Claro " + nombre + ", estos son nuestros estudios:\n" + textoEstudios(),
                    true, opcionesEstudios(), tramite));
        }
        return Optional.empty();
    }

    private Optional<ResultadoBot> procesarConfirmado(Paciente paciente, Tramite tramite, String norm) {
        String nombre = paciente.getNombre().split(" ")[0];
        if (norm.contains("cambiar fecha") || norm.contains("cambiar la cita") || norm.contains("cambiar mi cita")
                || norm.contains("reprogramar") || norm.contains("mover mi cita")) {
            return Optional.of(new ResultadoBot("Por WhatsApp no puedo cambiar la fecha de una cita ya confirmada, " + nombre
                    + ". Llama al Call Center y un asesor la reprograma por ti. Tu cita sigue siendo el "
                    + (tramite.getFechaCita() == null ? "sin fecha" : tramite.getFechaCita().format(FORMATO_LATINO_HORA))
                    + " en " + nombreClinica(tramite) + ".", true, List.of("¿Cuándo es mi cita?", "Ayuda"), tramite));
        }
        if (norm.contains("cita") || norm.contains("cuando") || norm.contains("fecha") || norm.contains("donde")
                || norm.contains("hora") || norm.contains("mi cita")) {
            return Optional.of(new ResultadoBot(resumenEstado(paciente, tramite), true, List.of("Ayuda"), tramite));
        }
        return Optional.empty();
    }

    private Optional<ResultadoBot> procesarAtendido(Paciente paciente, Tramite tramite, String norm) {
        String nombre = paciente.getNombre().split(" ")[0];
        Optional<Estudio> estudio = elegirEstudio(norm);
        if (estudio.isPresent()) {
            Tramite nuevo = tramites.crearTramite(paciente.getId(), estudio.get().getId(), "WHATSAPP");
            nuevo = tramites.cotizar(nuevo.getId(), estudio.get().getId());
            return Optional.of(new ResultadoBot("Tu trámite " + tramite.getFolio() + " ya fue atendido. Te creé uno nuevo: "
                    + nuevo.getFolio() + " — " + estudio.get().getNombre() + " por $" + nuevo.getPrecioCotizado()
                    + ". Ahora elige clínica.", true, opcionesClinicas(), nuevo));
        }
        if (quiereCotizar(norm)) {
            return Optional.of(new ResultadoBot("Claro " + nombre + ", estos son nuestros estudios:\n" + textoEstudios(),
                    true, opcionesEstudios(), tramite));
        }
        return Optional.empty();
    }

    /** Ayuda: qué puede hacer el bot EN ESTE ESTADO, con ejemplos. Nunca una respuesta vacía. */
    private ResultadoBot ayuda(Paciente paciente, Tramite tramite) {
        String nombre = paciente.getNombre().split(" ")[0];
        StringBuilder sb = new StringBuilder("Hola ").append(nombre).append(", soy Salud Digna 👋. ");
        switch (tramite.getEstado()) {
            case COTIZADO -> sb.append("Tu trámite ").append(tramite.getFolio())
                    .append(" ya está cotizado. Puedo ayudarte a elegir clínica: responde con el número o el nombre de la clínica.");
            case CLINICA_SELECCIONADA -> sb.append("Ya tienes clínica en tu trámite ").append(tramite.getFolio())
                    .append(". Puedo confirmar tu cita: dime el día en formato DD/MM/AAAA, o dime \"hoy\" o \"mañana\".");
            case INICIADO, DATOS_PENDIENTES -> sb.append("Puedo cotizarte un estudio: dime cuál necesitas (ultrasonido, perfil tiroideo, radiografía o análisis de sangre).");
            case CONFIRMADO -> sb.append("Tu cita ya está confirmada. Puedo decirte cuándo y dónde es.");
            case ATENDIDO -> sb.append("Tu trámite ya fue atendido. Puedo cotizarte otro estudio.");
        }
        sb.append("\n").append(listarOpcionesNumeradas(opcionesPara(tramite)));
        return new ResultadoBot(sb.toString(), true, opcionesPara(tramite), tramite);
    }

    /** Entrada no reconocida: lo dice claro, muestra el estado y las opciones válidas ahora. Nunca inventa un avance. */
    private ResultadoBot noEntendi(Paciente paciente, Tramite tramite, String mensajeCrudo) {
        List<String> opciones = opcionesPara(tramite);
        String texto = "No entendí \"" + mensajeCrudo.trim() + "\". " + resumenEstado(paciente, tramite)
                + "\nAhora puedes:\n" + listarOpcionesNumeradas(opciones);
        return new ResultadoBot(texto, false, opciones, tramite);
    }

    /** Resumen en una línea del folio: estudio, precio, estado y qué falta. */
    private String resumenEstado(Paciente paciente, Tramite tramite) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tu trámite ").append(tramite.getFolio()).append(" (").append(nombreEstudio(tramite));
        if (tramite.getPrecioCotizado() != null) sb.append(" $").append(tramite.getPrecioCotizado());
        sb.append(") está en estado ").append(tramite.getEstado().name()).append(": ");
        switch (tramite.getEstado()) {
            case INICIADO -> sb.append("aún no tiene estudio elegido");
            case DATOS_PENDIENTES -> sb.append("le faltan datos por completar");
            case COTIZADO -> sb.append("ya está cotizado, falta elegir clínica");
            case CLINICA_SELECCIONADA -> sb.append("clínica ").append(nombreClinica(tramite)).append(", falta confirmar la fecha");
            case CONFIRMADO -> sb.append("cita el ")
                    .append(tramite.getFechaCita() == null ? "sin fecha" : tramite.getFechaCita().format(FORMATO_LATINO_HORA))
                    .append(" en ").append(nombreClinica(tramite));
            case ATENDIDO -> sb.append("ya fue atendido");
        }
        sb.append(".");
        return sb.toString();
    }

    private String nombreEstudio(Tramite tramite) {
        if (tramite.getEstudioId() == null) return "sin estudio aún";
        return estudios.findById(tramite.getEstudioId()).map(Estudio::getNombre).orElse("estudio");
    }

    private String nombreClinica(Tramite tramite) {
        if (tramite.getClinicaId() == null) return "sin clínica";
        return clinicas.findById(tramite.getClinicaId()).map(Clinica::getNombre).orElse("clínica");
    }

    /** Respuestas sugeridas para el estado actual: el front las muestra como botones. */
    private List<String> opcionesPara(Tramite tramite) {
        return switch (tramite.getEstado()) {
            case COTIZADO -> opcionesClinicas();
            case CLINICA_SELECCIONADA -> opcionesFechas();
            case INICIADO, DATOS_PENDIENTES -> opcionesEstudios();
            case CONFIRMADO -> List.of("¿Cuándo es mi cita?", "Ayuda");
            case ATENDIDO -> opcionesEstudios();
        };
    }

    private List<String> opcionesClinicas() {
        List<Clinica> lista = clinicas.findAll();
        List<String> opciones = new ArrayList<>();
        for (int i = 0; i < lista.size(); i++) {
            opciones.add((i + 1) + " · " + lista.get(i).getNombre());
        }
        return opciones;
    }

    private List<String> opcionesFechas() {
        return List.of("Hoy", "Mañana", manana(), "Cambiar clínica");
    }

    private List<String> opcionesEstudios() {
        return estudios.findAll().stream()
                .map(e -> e.getNombre() + " $" + e.getPrecio().toPlainString())
                .toList();
    }

    private String listarClinicasTexto(String nombre, Tramite tramite) {
        List<Clinica> lista = clinicas.findAll();
        StringBuilder sb = new StringBuilder();
        sb.append("Perfecto ").append(nombre).append(", continuamos tu trámite ").append(tramite.getFolio())
                .append(". Elige una clínica:\n");
        for (int i = 0; i < lista.size(); i++) {
            sb.append(i + 1).append(") ").append(lista.get(i).getNombre())
                    .append(" (").append(lista.get(i).getCiudad()).append(")\n");
        }
        sb.append("Responde 1 o 2, o escribe el nombre de la clínica.");
        return sb.toString();
    }

    private String textoEstudios() {
        List<Estudio> lista = estudios.findAll();
        StringBuilder sb = new StringBuilder("Estos son nuestros estudios:\n");
        for (Estudio e : lista) {
            sb.append("- ").append(e.getNombre()).append(" — $").append(e.getPrecio().toPlainString()).append("\n");
        }
        sb.append("Dime cuál necesitas.");
        return sb.toString();
    }

    private String listarOpcionesNumeradas(List<String> opciones) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < opciones.size(); i++) {
            sb.append(i + 1).append(") ").append(opciones.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    private boolean esSaludoOAyuda(String norm) {
        return norm.equals("hola") || norm.startsWith("hola ") || norm.contains("ayuda")
                || norm.contains("opciones") || norm.contains("que puedo hacer") || norm.contains("que puedes hacer")
                || norm.contains("menu") || norm.contains("buenos dias") || norm.contains("buenas tardes")
                || norm.contains("buenas noches") || norm.equals("buenas") || norm.equals("hi") || norm.equals("hello");
    }

    private boolean quiereCotizar(String norm) {
        return norm.contains("cotizar") || norm.contains("cotizacion") || norm.contains("quiero un estudio")
                || norm.contains("quiero cotizar") || norm.contains("empezar") || norm.contains("nuevo tramite")
                || norm.contains("otro estudio") || norm.contains("necesito un estudio") || norm.contains("estudio");
    }

    /**
     * Elegir clínica: "1", "uno", "primera", "la primera", "puebla",
     * "centro", "juarez", "la de puebla", "2", "dos", "segunda",
     * "la segunda", "tehuacan", "la de tehuacan", "quiero la de Puebla"
     * y el nombre de la clínica. Por número se usa el orden del catálogo.
     */
    private Optional<Clinica> elegirClinica(String norm) {
        List<Clinica> lista = clinicas.findAll();
        if (lista.isEmpty()) return Optional.empty();
        String token = norm.trim();
        if (token.equals("1") || token.equals("uno") || norm.contains("primera")
                || norm.contains("la 1") || norm.contains("la uno")) {
            return Optional.of(lista.get(0));
        }
        if (token.equals("2") || token.equals("dos") || norm.contains("segunda")
                || norm.contains("la 2") || norm.contains("la dos")) {
            return Optional.of(lista.size() > 1 ? lista.get(1) : lista.get(0));
        }
        if (norm.contains("puebla") || norm.contains("centro") || norm.contains("juarez")) {
            return lista.stream().filter(c -> normalizar(c.getNombre()).contains("puebla")).findFirst()
                    .or(() -> Optional.of(lista.get(0)));
        }
        if (norm.contains("tehuacan")) {
            return lista.stream().filter(c -> normalizar(c.getNombre()).contains("tehuacan")).findFirst()
                    .or(() -> lista.size() > 1 ? Optional.of(lista.get(1)) : Optional.of(lista.get(0)));
        }
        for (Clinica c : lista) {
            String n = normalizar(c.getNombre());
            if (norm.contains(n) || (norm.length() > 4 && n.contains(norm))) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    private Optional<Estudio> elegirEstudio(String norm) {
        for (Estudio e : estudios.findAll()) {
            String n = normalizar(e.getNombre());
            if (!n.isBlank() && norm.contains(n)) return Optional.of(e);
            if (norm.contains("ultrasonido") && n.contains("ultrasonido")) return Optional.of(e);
            if ((norm.contains("tiroideo") || norm.contains("tiroides")) && n.contains("tiroideo")) return Optional.of(e);
            if (norm.contains("radiografia") && n.contains("radiografia")) return Optional.of(e);
            if ((norm.contains("analisis") || norm.contains("sangre") || norm.contains("biometria")) && n.contains("analisis")) return Optional.of(e);
        }
        return Optional.empty();
    }

    private static String normalizar(String s) {
        if (s == null) return "";
        String sinAcentos = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinAcentos.toLowerCase().trim();
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
