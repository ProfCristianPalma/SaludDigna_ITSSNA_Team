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
import mx.saluddigna.omnicanal.identidad.CurpUtil;
import mx.saluddigna.omnicanal.identidad.PacienteService;
import mx.saluddigna.omnicanal.identidad.domain.Paciente;
import mx.saluddigna.omnicanal.seguridad.PasswordService;
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
 * - Elegir folio ES una intención entendida ("folio 1", "primer
 *   folio", "SD-2026-0012"): el bot lo confirma con entendido=true;
 *   nunca responde "No entendí" a un mensaje que ya seleccionó
 *   el folio (la selección se resuelve antes de la intención).
 * - "opcion N" / "numero N" (y un número solo donde no significa
 *   otra cosa) eligen la opción N de la lista que el bot acaba de
 *   ofrecer EN ESE NIVEL: menú (sin folio en contexto), folios,
 *   estudios, clínicas u horas. Un número solo SIN folio en contexto
 *   también es opción de menú ("2" = iniciar un trámite nuevo) o el
 *   folio N de la lista (3+); antes un "2" pelado caía en el
 *   auto-resolve del único folio activo (captura Eduardo, 2026-11-24).
 *   En COTIZADO "1" sigue siendo la clínica 1 y en CLINICA_SELECCIONADA un número solo sigue
 *   siendo una hora: ahí no se toca.
 * - "regresar" / "atrás" es un nivel atrás sin deshacer datos (en
 *   CLINICA_SELECCIONADA vuelve a elegir clínica; en COTIZADO
 *   re-muestra la cotización; en CONFIRMADO NO retrocede nada: muestra
 *   el resumen de la cita con sus opciones útiles, sin decir "volvemos
 *   un paso atrás"; sin folio en contexto va al menú);
 *   "volver" a secas sigue siendo cambiar de folio con folio en
 *   contexto; "volver al inicio" / "volver al menú" / "inicio" /
 *   "principal" va al menú principal con la CURP recordada (nunca se
 *   olvida la CURP). El menú y el "¿qué estudio necesitas?" llevan
 *   `limpiarFolio: true` para que el front suelte el folio anterior.
 * - "nuevo" es la señal de crear ("quiero un nuevo tramite",
 *   "quiero reservar/agendar un estudio", "otra cita") y
 *   "continuar/seguir" es la de retomar el activo: nunca se
 *   confunden. El trámite NUNCA se crea antes de nombrar el
 *   estudio y pasar el chequeo de duplicado: si ya hay un activo
 *   de ese estudio se retoma ese folio, y si quedó un folio vacío
 *   en contexto se borra (TramiteService.eliminarTramiteVacio).
 * - Lo que el bot no entiende queda en el log como evento
 *   mensaje.no_entendido con el texto crudo (solo con trámite en
 *   contexto: el evento necesita un tramite_id).
 * - Un "hola" o "ayuda" NO crea trámites: solo se crea uno cuando el
 *   usuario pide un estudio concreto. La identidad es SOLO por CURP:
 *   el request trae `curp` (o la CURP viene en el mensaje) y, si no
 *   hay CURP, el bot la PIDE antes de tocar ningún dato del paciente.
 *   Quien escribe la CURP del paciente (una tercera persona) puede
 *   continuar su trámite. Un CURP no registrado se registra con
 *   nombre + CURP + fecha de nacimiento + contraseña en el mismo chat.
 * - La reservación es completa por el chat: estudio → clínica →
 *   fecha y hora → confirmar. Al pedir solo la fecha, el bot ofrece
 *   las horas libres de ese día (parrilla 07:00–17:00, slots de
 *   30 min); si la hora elegida está ocupada, lo dice y ofrece las
 *   horas libres más cercanas; si es válida, confirma y responde con
 *   folio, clínica, fecha y hora en DD/MM/AAAA HH:mm.
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
    private final PasswordService passwords;

    public CanalController(PacienteService pacientes, TramiteService tramites,
                           EventoService eventos, ClinicaRepository clinicas,
                           EstudioRepository estudios, PasswordService passwords) {
        this.pacientes = pacientes;
        this.tramites = tramites;
        this.eventos = eventos;
        this.clinicas = clinicas;
        this.estudios = estudios;
        this.passwords = passwords;
    }

    /**
     * Mensaje de WhatsApp. `curp` es opcional en el JSON pero es la
     * ÚNICA identidad: si no viene (o no está registrada) el bot la
     * busca en el mensaje y, si tampoco está ahí, la pide antes de
     * hacer cualquier cosa con datos del paciente. `folio` es opcional:
     * es el folio en contexto que manda el front con cada mensaje (el
     * que el usuario venía siguiendo), para no tener que adivinarlo
     * entre varios folios. El campo `telefono` se eliminó (2026-11-24):
     * el teléfono ya no identifica a nadie en este canal.
     */
    public record MensajeWhatsappRequest(String curp, @NotBlank String mensaje, String folio,
                                         // Nivel de la última respuesta del bot que el
                                         // front reenvía ("ELEGIR_ESTUDIO", "ELEGIR_FOLIO",
                                         // "ELEGIR_ACCION"...): la selección por número se
                                         // resuelve contra la última lista ofrecida en ese
                                         // nivel (captura Ana, 2026-11-24: tras la lista
                                         // de estudios, "6" es el estudio 6, no el folio
                                         // 6). Opcional: sin nivel se conserva lo anterior.
                                         String nivel) {
    }

    public record NotaCallCenterRequest(UUID tramiteId, String nota) {
    }

    /** Lo que el bot entendió (o no) y qué responde. `tramite` es el trámite tras aplicar la intención, si cambió.
     * `limpiarFolio`: la respuesta suelta el folio en contexto (iniciar un trámite nuevo desde una cita
     * confirmada): la siguiente respuesta se resuelve sin contexto, contra la lista de estudios. */
    private record ResultadoBot(String texto, boolean entendido, List<String> opciones, Tramite tramite, boolean limpiarFolio) {
        ResultadoBot(String texto, boolean entendido, List<String> opciones, Tramite tramite) {
            this(texto, entendido, opciones, tramite, false);
        }

        ResultadoBot(String texto, boolean entendido, List<String> opciones) {
            this(texto, entendido, opciones, null, false);
        }
    }

    private static final Pattern FECHA_ISO = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");

    /** Fecha latinoamericana DD/MM/AAAA (dia/mes/ano, no mes/dia). */
    private static final Pattern FECHA_LATINA = Pattern.compile("(\\d{1,2})/(\\d{1,2})/(\\d{4})");

    /** Fecha corta DD/MM (año actual): "25/11". No casa con DD/MM/AAAA (eso lo cubre FECHA_LATINA). */
    private static final Pattern FECHA_CORTA = Pattern.compile("(\\d{1,2})/(\\d{1,2})(?!/)");

    /** "el 25 de noviembre", "25 de noviembre de 2026". */
    private static final Pattern FECHA_MES_NOMBRE = Pattern.compile(
            "(?:el\\s+)?(\\d{1,2})\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|setiembre|octubre|noviembre|diciembre)(?:\\s+de\\s+(\\d{4}))?",
            Pattern.CASE_INSENSITIVE);

    /** "el 25": día del mes actual. */
    private static final Pattern FECHA_EL_DIA = Pattern.compile("\\bel\\s+(\\d{1,2})\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Hora, tolerante: "10:00", "10.30", "10:00 am/pm", "a las 10",
     * "las 10", "10 h", "10h30", "10 am", "10 pm" y un número suelto
     * ("10", solo si TODO el mensaje es el número). Antes solo valían
     * "HH:MM", "a las H" y "H h": cualquier otra hora que escribiera
     * el usuario caía en "formato no válido" aunque fuera clara.
     */
    private static final Pattern HORA_COMPLETA = Pattern.compile(
            "(\\d{1,2})[:.](\\d{2})\\s*(?:([ap])\\.?\\s*m\\.?)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern HORA_A_LAS = Pattern.compile(
            "a\\s+las?\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(?:([ap])\\.?\\s*m\\.?)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern HORA_LAS = Pattern.compile(
            "\\blas\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(?:([ap])\\.?\\s*m\\.?)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern HORA_H = Pattern.compile(
            "(\\d{1,2})\\s*h(?:oras?)?\\s*(\\d{2})?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HORA_AMPM = Pattern.compile(
            "(\\d{1,2})\\s*([ap])\\.?\\s*m\\.?\\b", Pattern.CASE_INSENSITIVE);

    /** Formato en el que se le muestran las fechas al usuario: DD/MM/AAAA. */
    private static final DateTimeFormatter FORMATO_LATINO = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final DateTimeFormatter FORMATO_LATINO_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** Formato en el que se le muestra la hora al usuario: HH:mm. */
    private static final DateTimeFormatter FORMATO_HORA = DateTimeFormatter.ofPattern("HH:mm");

    /** Mañana en DD/MM/AAAA (fecha sugerida para confirmar la cita). */
    private static String manana() {
        return LocalDate.now().plusDays(1).format(FORMATO_LATINO);
    }

    /**
     * Extrae fecha Y hora del mensaje ("25/11/2026 10:00", "25/11 a las 10",
     * "el 25", "el 25 de noviembre", "hoy", "mañana", "pasado mañana").
     * Sin hora se usa las 10:00 (comportamiento actual). Devuelve vacío
     * si no hay fecha o si la fecha/hora no es real (31/02/2026, 25:00).
     */
    static Optional<LocalDateTime> extraerFechaHora(String mensaje) {
        Optional<LocalDate> fecha = extraerFechaDeMensaje(mensaje);
        if (fecha.isEmpty()) return Optional.empty();
        Optional<LocalTime> hora = extraerHora(mensaje);
        if (hora.isEmpty() && pareceHora(mensaje)) return Optional.empty(); // hora escrita pero imposible (25:00)
        return Optional.of(LocalDateTime.of(fecha.get(), hora.orElse(LocalTime.of(10, 0))));
    }

    /** Solo la fecha de extraerFechaHora: DD/MM/AAAA, AAAA-MM-DD, DD/MM, "el DD (de <mes>)", "hoy"/"mañana"/"pasado mañana". */
    private static Optional<LocalDate> extraerFechaDeMensaje(String mensaje) {
        Optional<LocalDate> explicita = extraerFechaExplicita(mensaje);
        if (explicita.isPresent()) return explicita;
        if (mensaje == null) return Optional.empty();
        Matcher mes = FECHA_MES_NOMBRE.matcher(mensaje);
        if (mes.find()) {
            try {
                int anio = mes.group(3) == null ? LocalDate.now().getYear() : Integer.parseInt(mes.group(3));
                return Optional.of(LocalDate.of(anio, mesDeNombre(mes.group(2)), Integer.parseInt(mes.group(1))));
            } catch (java.time.DateTimeException e) {
                return Optional.empty();
            }
        }
        Matcher corta = FECHA_CORTA.matcher(mensaje);
        if (corta.find()) {
            try {
                return Optional.of(LocalDate.of(LocalDate.now().getYear(),
                        Integer.parseInt(corta.group(2)), Integer.parseInt(corta.group(1))));
            } catch (java.time.DateTimeException e) {
                return Optional.empty();
            }
        }
        Matcher dia = FECHA_EL_DIA.matcher(mensaje);
        if (dia.find()) {
            try {
                LocalDate hoy = LocalDate.now();
                return Optional.of(LocalDate.of(hoy.getYear(), hoy.getMonth(), Integer.parseInt(dia.group(1))));
            } catch (java.time.DateTimeException e) {
                return Optional.empty();
            }
        }
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

    private static int mesDeNombre(String nombre) {
        return switch (normalizar(nombre)) {
            case "enero" -> 1;
            case "febrero" -> 2;
            case "marzo" -> 3;
            case "abril" -> 4;
            case "mayo" -> 5;
            case "junio" -> 6;
            case "julio" -> 7;
            case "agosto" -> 8;
            case "septiembre", "setiembre" -> 9;
            case "octubre" -> 10;
            case "noviembre" -> 11;
            case "diciembre" -> 12;
            default -> 0; // no llega: el regex solo casa meses validos
        };
    }

    /** Hora 12h -> 24h: "10 pm" -> 22:00, "12 am" -> 00:00, "12 pm" -> 12:00. Sin am/pm, tal cual. Vacío si es imposible (25:00, 14 pm). */
    private static Optional<LocalTime> horaConAmPm(int hora, int minuto, String ampm) {
        try {
            if (ampm != null) {
                String a = ampm.toLowerCase();
                if (hora < 1 || hora > 12) return Optional.empty();
                if (a.equals("p") && hora != 12) hora += 12;
                if (a.equals("a") && hora == 12) hora = 0;
            }
            return Optional.of(LocalTime.of(hora, minuto));
        } catch (java.time.DateTimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Hora escrita en el mensaje ("10:00", "10.30", "10:00 am", "a las 10",
     * "las 10", "10 h", "10h30", "10 am", "10"). Vacío si no hay hora o
     * si es imposible (25:00, 10:75). Un número suelto solo vale si TODO
     * el mensaje es el número ("folio 2" no es una hora: eso se resuelve
     * antes, como selección de folio).
     */
    private static Optional<LocalTime> extraerHora(String mensaje) {
        if (mensaje == null) return Optional.empty();
        Matcher completa = HORA_COMPLETA.matcher(mensaje);
        if (completa.find()) {
            return horaConAmPm(Integer.parseInt(completa.group(1)), Integer.parseInt(completa.group(2)), completa.group(3));
        }
        Matcher aLas = HORA_A_LAS.matcher(mensaje);
        if (aLas.find()) {
            int min = aLas.group(2) == null ? 0 : Integer.parseInt(aLas.group(2));
            return horaConAmPm(Integer.parseInt(aLas.group(1)), min, aLas.group(3));
        }
        Matcher las = HORA_LAS.matcher(mensaje);
        if (las.find()) {
            int min = las.group(2) == null ? 0 : Integer.parseInt(las.group(2));
            return horaConAmPm(Integer.parseInt(las.group(1)), min, las.group(3));
        }
        Matcher h = HORA_H.matcher(mensaje);
        if (h.find()) {
            int min = h.group(2) == null ? 0 : Integer.parseInt(h.group(2));
            try {
                return Optional.of(LocalTime.of(Integer.parseInt(h.group(1)), min));
            } catch (java.time.DateTimeException e) {
                return Optional.empty();
            }
        }
        Matcher ampm = HORA_AMPM.matcher(mensaje);
        if (ampm.find()) {
            return horaConAmPm(Integer.parseInt(ampm.group(1)), 0, ampm.group(2));
        }
        String t = mensaje.trim().replaceAll("[.,;:]+$", "");
        if (t.matches("\\d{1,2}")) {
            try {
                return Optional.of(LocalTime.of(Integer.parseInt(t), 0));
            } catch (java.time.DateTimeException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** ¿El mensaje trae algo que parece una hora escrita? (para distinguir "hora imposible" de "no era una hora"). */
    private static boolean pareceHora(String mensaje) {
        if (mensaje == null) return false;
        return HORA_COMPLETA.matcher(mensaje).find() || HORA_A_LAS.matcher(mensaje).find()
                || HORA_LAS.matcher(mensaje).find() || HORA_H.matcher(mensaje).find()
                || HORA_AMPM.matcher(mensaje).find()
                || mensaje.trim().replaceAll("[.,;:]+$", "").matches("\\d{1,2}");
    }

    /** ¿El mensaje trae algo que parece fecha u hora? (fecha imposible u hora imposible, no "eso no era una fecha"). */
    private static boolean pareceFechaHora(String mensaje) {
        if (mensaje == null) return false;
        return pareceFecha(mensaje) || FECHA_CORTA.matcher(mensaje).find()
                || FECHA_MES_NOMBRE.matcher(mensaje).find() || FECHA_EL_DIA.matcher(mensaje).find()
                || pareceHora(mensaje) || extraerFecha(mensaje).isPresent();
    }

    /**
     * Extrae una fecha del mensaje del usuario. Acepta DD/MM/AAAA
     * (formato latinoamericano), AAAA-MM-DD (compatibilidad), DD/MM,
     * "el DD (de <mes>)" y las palabras "hoy", "mañana" y "pasado
     * mañana". Devuelve vacío si no hay fecha o si no es una fecha
     * real (31/02/2026). Delega en extraerFechaHora (la hora se ignora aquí).
     */
    static Optional<LocalDate> extraerFecha(String mensaje) {
        return extraerFechaHora(mensaje).map(LocalDateTime::toLocalDate);
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
     * El grupo 1 es la palabra ("folio" | "opcion" | "numero"): con folio en
     * contexto, "opcion N" / "numero N" NO es seleccion de folio (lo resuelve
     * procesarIntencion segun el nivel: clinica, hora o estudio); solo
     * "folio N" selecciona un folio con contexto. Sin contexto, las tres
     * formas seleccionan de la lista (salvo "opcion 1"/"opcion 2", que son
     * las opciones del menu: ver mis folios / tramite nuevo).
     */
    private static final Pattern SELECCION_PATTERN =
            Pattern.compile("(folio|opci[oó]n|n[uú]mero)\\s*(\\d{1,2})", Pattern.CASE_INSENSITIVE);

    /**
     * Ordinales en palabras para elegir de la lista ("primer folio",
     * "el segundo", "la primera", "último"). Las clínicas son
     * femeninas ("la primera clínica"), así que un ordinal femenino
     * solo elige folio cuando NO hay un folio en contexto; con
     * contexto, "la primera" es la clínica 1. Los masculinos
     * ("primero", "segundo", "último") siempre eligen folio.
     * Se aplican sobre el texto normalizado (sin acentos, minúsculas).
     */
    private static final Pattern ORDINAL_MASC =
            Pattern.compile("\\b(primer|primero|segundo|tercero|cuarto|quinto|ultimo)\\b");
    private static final Pattern ORDINAL_FEM =
            Pattern.compile("\\b(primera|segunda|tercera|cuarta|quinta|ultima)\\b");

    @PostMapping("/whatsapp/mensaje")
    public Map<String, Object> mensajeWhatsapp(@RequestBody MensajeWhatsappRequest req) {
        // La identidad es SOLO la CURP: viene en el campo `curp` del
        // request o escrita en el mensaje. Sin CURP (ni registrada) el
        // bot la pide antes de tocar ningún dato del paciente.
        String texto = req.mensaje() == null ? "" : req.mensaje().trim();
        String norm = normalizar(texto);
        Optional<Paciente> pacienteOpt = resolverPorCurp(req.curp(), texto);
        if (pacienteOpt.isEmpty()) {
            return responderRegistro(req.curp(), texto, norm);
        }
        Paciente paciente = pacienteOpt.get();
        // Un paciente puede tener varios folios activos, uno por estudio.
        List<Tramite> activos = tramites.listarActivosDe(paciente.getId());

        // "cambiar de CURP" / "otra CURP": olvidar la CURP recordada
        // (o cambiar de paciente si el mensaje trae otra CURP).
        // Va antes de listar/volver: es una orden sobre la identidad.
        if (esCambiarCurp(norm)) {
            Optional<String> nueva = CurpUtil.buscarEnTexto(texto);
            if (nueva.isEmpty() || nueva.get().equalsIgnoreCase(paciente.getCurp())) {
                return respuestaOlvidaCurp();
            }
            Optional<Paciente> porNueva = pacientes.buscarPorCurp(nueva.get());
            if (porNueva.isEmpty()) {
                return responderRegistro(nueva.get(), texto, norm);
            }
            Paciente nuevoPaciente = porNueva.get();
            List<Tramite> activosNuevo = tramites.listarActivosDe(nuevoPaciente.getId());
            return respuestaMenu(nuevoPaciente, activosNuevo);
        }

        // La intención de listar o de volver manda sobre el contexto:
        // "muestrame mis folios" con un folio en contexto NO debe
        // devolver el estado de ese folio (captura 2, 2026-11-24), y
        // "folios" debe listar sin "No entendí" (captura 1). Por eso
        // se comprueba ANTES de resolver folio/contexto/intención.
        // "folio 1" NO es pedir lista: si el texto trae una selección
        // explícita (folio N o SD-2026-XXXX), esa manda.
        if (esPedirLista(norm) && !SELECCION_PATTERN.matcher(texto).find() && !FOLIO_PATTERN.matcher(texto).find()) {
            if (activos.isEmpty()) {
                return respuestaSinTramite("Hola " + paciente.getNombre().split(" ")[0]
                        + ", aún no tienes trámites activos. Puedo cotizarte un estudio:\n" + textoEstudios(),
                        "ELEGIR_ESTUDIO", true, opcionesEstudios());
            }
            return respuestaListaFolios(paciente, activos, texto, norm);
        }
        // "volver al inicio" / "volver al menú": menú principal con la
        // CURP recordada. NUNCA se olvida la CURP aquí: para eso está
        // "cambiar de CURP". Va antes de pedir-lista/volver: es una
        // orden sobre el nivel, no sobre un folio.
        if (esVolverAlMenu(norm)) {
            return respuestaMenu(paciente, activos);
        }
        // "regresar" / "atrás": un nivel atrás sin deshacer datos (en
        // CLINICA_SELECCIONADA vuelve a elegir clínica; en COTIZADO
        // re-muestra la cotización; sin folio en contexto va al menú).
        // "volver" a secas NO es esto: es cambiar de folio (abajo).
        if (esRegresarNivel(norm)) {
            return respuestaRegresarNivel(paciente, activos, req.folio());
        }
        if (esVolver(norm)) {
            // "volver" a secas es cambiar de folio: con un folio en
            // contexto, lista los folios (como antes). Sin folio en
            // contexto no hay de qué cambiar: menú principal con la
            // CURP recordada (ya no se olvida la CURP por decir
            // "volver"; para eso está "cambiar de CURP").
            if (req.folio() == null || req.folio().isBlank()) {
                return respuestaMenu(paciente, activos);
            }
            if (activos.isEmpty()) {
                return respuestaSinTramite("Hola " + paciente.getNombre().split(" ")[0]
                        + ", aún no tienes trámites activos. Puedo cotizarte un estudio:\n" + textoEstudios(),
                        "ELEGIR_ESTUDIO", true, opcionesEstudios());
            }
            return respuestaListaFolios(paciente, activos, texto, norm,
                    "Claro, ¿con cuál seguimos? Tienes " + activos.size() + " trámites activos.");
        }

        // Si el mensaje selecciona un folio, ESA es su intención y se
        // marca como entendida: nunca debe terminar en "No entendí" un
        // mensaje que ya eligió el folio (captura 2026-11-24: "folio 1"
        // seleccionaba SD-2026-0012 y a la vez decía "No entendí").
        Tramite tramite = null;
        boolean folioSeleccionado = false;
        boolean conContexto = req.folio() != null && !req.folio().isBlank();

        // Identificación por CURP (mensaje solo con la CURP, con saludo
        // o con "cambiar de CURP"): la CURP ES una intención entendida,
        // no un "No entendí". El bot confirma y da las dos opciones:
        // ver los folios o iniciar un trámite nuevo. Nunca lista los
        // folios directo (captura 1, 2026-11-24).
        Optional<String> curpEnMensaje = CurpUtil.buscarEnTexto(texto);
        if (esMenuInicial(norm, curpEnMensaje.isPresent(), conContexto, paciente)) {
            return respuestaMenu(paciente, activos);
        }

        // La selección se resuelve contra la ÚLTIMA LISTA que el bot
        // ofreció en ese nivel (captura Ana, 2026-11-24): tras la lista
        // de estudios, "6" es el estudio 6, no el folio 6. El front
        // reenvía el nivel de la última respuesta del bot; sin nivel se
        // conserva el comportamiento anterior (folio N).
        if (!conContexto && "ELEGIR_ESTUDIO".equals(req.nivel())) {
            Optional<Integer> nSel = seleccionOpcionPura(norm);
            if (nSel.isEmpty()) nSel = numeroSolo(norm);
            if (nSel.isPresent()) {
                List<Estudio> listaEstudios = estudios.findAll();
                int n = nSel.get();
                if (n >= 1 && n <= listaEstudios.size()) {
                    return respuestaEstudioElegido(paciente, listaEstudios.get(n - 1));
                }
                return respuestaSinTramite("No hay un estudio " + n + ": tenemos " + listaEstudios.size()
                        + " estudios. ¿Cuál necesitas?\n" + textoEstudios(),
                        "ELEGIR_ESTUDIO", true, opcionesEstudios(), paciente.getCurp());
            }
        }
        // "opcion 1" / "numero 2" sin folio en contexto: eligen del MENÚ
        // ("Ver mis folios" / "Iniciar un trámite nuevo"). Pero si lo
        // último ofrecido fue la LISTA DE FOLIOS, un número solo o
        // "opcion N" es el folio N (no opción de menú): con nivel ya se
        // distingue el menú de la lista, y el menú deja de mandar para
        // 1 y 2 en ese caso. Sin nivel, a partir de 3 es el folio N de
        // la lista (como antes) y 1-2 son del menú.
        if (!conContexto && !"ELEGIR_FOLIO".equals(req.nivel())) {
            // Un número solo también es opción de menú ("2" = iniciar un
            // trámite nuevo): antes solo valían "opcion 2"/"numero 2" y
            // un "2" pelado caía en el auto-resolve del único folio
            // activo (captura Eduardo, 2026-11-24: saltaba a su cita
            // confirmada en vez de preguntar el estudio).
            Optional<Integer> selMenu = seleccionOpcionPura(norm);
            if (selMenu.isEmpty()) selMenu = numeroSolo(norm);
            if (selMenu.isPresent() && selMenu.get() == 1) {
                if (activos.isEmpty()) {
                    return respuestaSinTramite("Hola " + paciente.getNombre().split(" ")[0]
                            + ", aún no tienes trámites activos. Puedo cotizarte un estudio:\n" + textoEstudios(),
                            "ELEGIR_ESTUDIO", true, opcionesEstudios());
                }
                return respuestaListaFolios(paciente, activos, texto, norm,
                        "Hola " + paciente.getNombre().split(" ")[0] + ", tienes " + activos.size()
                                + " trámites activos. ¿Con cuál seguimos?");
            }
            if (selMenu.isPresent() && selMenu.get() == 2) {
                return respuestaPreguntarEstudio(paciente);
            }
            // El menú ofrece solo 2 opciones: un 3+ no es opción de
            // menú ni folio (la lista de folios no se ofreció aquí, así
            // que un número solo no es folio N en este nivel).
            if (selMenu.isPresent() && "ELEGIR_ACCION".equals(req.nivel())) {
                return respuestaSinTramite("No hay una opción " + selMenu.get() + " en este paso. ¿Qué quieres hacer?\n"
                        + listarOpcionesNumeradas(List.of("Ver mis folios", "Iniciar un trámite nuevo")),
                        "ELEGIR_ACCION", true, List.of("Ver mis folios", "Iniciar un trámite nuevo"), paciente.getCurp());
            }
        }

        // "Iniciar un trámite nuevo" (o cotizar sin folio en contexto):
        // preguntar el estudio, antes de resolver folio/intención, para
        // no caer en la lista con "No entendí". El trámite YA NO se crea
        // aquí (bug 2026-11-24: se creaba un folio vacío INICIADO sin
        // estudio y luego, al nombrar el estudio, aparecía el duplicado):
        // se crea solo al nombrar el estudio y pasar el duplicado.
        boolean seleccionExplicita = SELECCION_PATTERN.matcher(texto).find()
                || FOLIO_PATTERN.matcher(texto).find()
                || ORDINAL_MASC.matcher(norm).find();
        // El mensaje nombra un estudio sin folio en contexto y sin
        // selección explícita: PRIMERO el chequeo de duplicado, nunca
        // crear antes. Si ya hay un activo de ese estudio se retoma ese
        // folio (y si había un único folio vacío huérfano, se borra);
        // si no hay duplicado y hay un único trámite vacío pendiente se
        // reutiliza (no se crea otro folio); si no, se crea CON estudio
        // y se cotiza en el mismo paso, como responderSinFolios.
        // "continuar/seguir/retomar/ver..." es consultar, no crear: eso
        // lo resuelve esConsultarTramite abajo.
        Optional<Estudio> estudioNombrado = elegirEstudio(norm);
        if (!seleccionExplicita && !conContexto && estudioNombrado.isPresent() && !esConsultarEstudio(norm)) {
            return respuestaEstudioElegido(paciente, estudioNombrado.get());
        }
        if (!seleccionExplicita && esNuevoTramiteExplicito(norm)) {
            return respuestaPreguntarEstudio(paciente);
        }
        if (!seleccionExplicita && !conContexto && esCotizacionGenerica(norm)) {
            List<Tramite> pendientes = activos.stream()
                    .filter(t -> t.getEstado() == EstadoTramite.INICIADO || t.getEstado() == EstadoTramite.DATOS_PENDIENTES)
                    .toList();
            if (pendientes.size() == 1) {
                // Hay un solo trámite sin estudio: preguntar el estudio
                // para ese folio, sin crear otro.
                Tramite pendiente = pendientes.get(0);
                return respuestaConTramite(pendiente, activos, new ResultadoBot("Claro, "
                        + paciente.getNombre().split(" ")[0] + ", sigamos con tu trámite " + pendiente.getFolio()
                        + " (aún sin estudio). ¿Qué estudio necesitas?\n" + textoEstudios(), true, opcionesEstudios()));
            }
            if (!activos.isEmpty()) {
                // Ya NO se crea un trámite vacío: solo se pregunta el
                // estudio (el trámite se crea al nombrarlo, con el
                // chequeo de duplicado de arriba).
                return respuestaPreguntarEstudio(paciente);
            }
            // Sin folios activos: no se crea trámite aún; solo se
            // ofrecen los estudios (el trámite se crea al elegir uno).
        }

        // Consultar el trámite ("quiero continuar mi tramite", "ver
        // mi reservacion", "mi cita"): el paciente habla de su
        // trámite/estudio/reservación, no elige una opción numérica ni
        // da una fecha/hora. Se responde con el resumen según el
        // estado (retomar con lo que falta, o la cita ya agendada).
        // Va antes de resolver el folio, como pedir-lista/volver.
        if (esConsultarTramite(norm, texto)) {
            return respuestaConsultarTramite(paciente, activos, req.folio());
        }

        // 1) Folio explícito en el texto (SD-2026-XXXX): manda sobre el contexto.
        Matcher folioEnTexto = FOLIO_PATTERN.matcher(texto);
        if (folioEnTexto.find()) {
            String folioPedido = folioEnTexto.group().toUpperCase();
            Tramite porFolio;
            try {
                porFolio = tramites.buscarPorFolio(folioPedido);
            } catch (ResponseStatusException e) {
                return respuestaSinTramite("No encontré ningún trámite con el folio " + folioPedido
                        + ". Revisa el folio o dime tu estudio para empezar uno nuevo.", "ELEGIR_FOLIO", true, List.of());
            }
            if (!porFolio.getPaciente().getId().equals(paciente.getId())) {
                return respuestaSinTramite("El folio " + folioPedido + " no pertenece a tu CURP.",
                        "ELEGIR_FOLIO", true, List.of());
            }
            tramite = porFolio;
            folioSeleccionado = true;
        } else {
            // 2) Selección de la lista en el texto ("folio 1", "opción 2",
            // "primer folio", "el segundo"): también manda sobre el contexto.
            int pedido = indicePedido(texto, norm, conContexto, activos.size());
            if (pedido != -1) {
                if (activos.isEmpty()) {
                    return respuestaSinTramite("No tienes trámites activos, " + paciente.getNombre().split(" ")[0]
                            + ". Puedo cotizarte un estudio:\n" + textoEstudios(), "ELEGIR_ESTUDIO", true, opcionesEstudios());
                }
                if (pedido < 1 || pedido > activos.size()) {
                    return respuestaFueraDeRango(paciente, activos, pedido);
                }
                tramite = activos.get(pedido - 1);
                folioSeleccionado = true;
            } else if (conContexto) {
                // 3) Folio en contexto (campo `folio` del front).
                String folioContexto = req.folio().trim().toUpperCase();
                Tramite porContexto;
                try {
                    porContexto = tramites.buscarPorFolio(folioContexto);
                } catch (ResponseStatusException e) {
                    return respuestaSinTramite("No encontré ningún trámite con el folio " + folioContexto
                            + ". Revisa el folio o dime tu estudio para empezar uno nuevo.", "ELEGIR_FOLIO", true, List.of());
                }
                if (!porContexto.getPaciente().getId().equals(paciente.getId())) {
                    return respuestaSinTramite("El folio " + folioContexto + " no pertenece a tu CURP.",
                            "ELEGIR_FOLIO", true, List.of());
                }
                tramite = porContexto;
            } else {
                // 4) La intención identifica el folio por su estado
                // (p. ej. "1" -> el folio COTIZADO; una fecha -> el
                // folio CLINICA_SELECCIONADA; un estudio -> el INICIADO).
                Optional<Tramite> porIntencion = porIntencion(norm, texto, activos);
                if (porIntencion.isPresent()) {
                    tramite = porIntencion.get();
                } else if (activos.size() == 1) {
                    // 5) Un solo folio activo: es ese.
                    tramite = activos.get(0);
                } else if (activos.isEmpty()) {
                    // 6) Sin folios: solo se crea un trámite si el usuario
                    // pide un estudio concreto; un saludo no crea nada.
                    return responderSinFolios(paciente, texto, norm);
                } else {
                    // 7) Varios folios y no se puede saber con cuál seguir:
                    // no se elige uno al azar, se listan y se pregunta.
                    return respuestaListaFolios(paciente, activos, texto, norm);
                }
            }
        }

        eventos.registrar(tramite.getId(), TipoEvento.CANAL_CAMBIADO, "WHATSAPP", "PACIENTE",
                "{\"mensaje\":\"" + escapar(texto) + "\",\"canalAnterior\":\"" + tramite.getCanalOrigen() + "\"}");

        // Interpreta la intencion segun el estado actual. Puede avanzar el tramite o crear uno nuevo.
        ResultadoBot resultado = procesarIntencion(paciente, tramite, texto, folioSeleccionado);
        if (resultado.limpiarFolio()) {
            // Iniciar un trámite nuevo desde una cita confirmada: se
            // suelta el folio confirmado (como al elegir "Iniciar un
            // trámite nuevo" en el menú). La siguiente respuesta se
            // resuelve sin contexto, contra la lista de estudios que se
            // acaba de ofrecer ("6" = estudio 6, no folio 6).
            Map<String, Object> resp = respuestaSinTramite(resultado.texto(), "ELEGIR_ESTUDIO", true,
                    resultado.opciones(), paciente.getCurp());
            resp.put("limpiarFolio", true);
            return resp;
        }
        Tramite actualizado = resultado.tramite() != null ? resultado.tramite() : tramites.buscar(tramite.getId());
        // La lista de folios se relee: el estado del folio pudo cambiar con este mensaje.
        List<Tramite> activosActuales = tramites.listarActivosDe(paciente.getId());
        return respuestaConTramite(actualizado, activosActuales, resultado);
    }

    /**
     * Resuelve la identidad SOLO por CURP: primero el campo `curp` del
     * request (si trae formato válido), y si no, la primera CURP que
     * aparezca en el mensaje. Un teléfono o un nombre nunca resuelven
     * a nadie aquí.
     */
    private Optional<Paciente> resolverPorCurp(String curpCampo, String texto) {
        if (curpCampo != null && !curpCampo.isBlank() && CurpUtil.esFormatoValido(curpCampo)) {
            Optional<Paciente> porCampo = pacientes.buscarPorCurp(curpCampo);
            if (porCampo.isPresent()) {
                return porCampo;
            }
        }
        return CurpUtil.buscarEnTexto(texto).flatMap(pacientes::buscarPorCurp);
    }

    /**
     * CURP no registrada (o sin CURP): el bot guía el registro en el
     * mismo chat. Sin CURP en el campo ni en el mensaje, la PIDE antes
     * de cualquier otra cosa (saludo inicial incluido): sin identidad
     * no se toca ningún dato del paciente. Con CURP, pide nombre
     * completo + fecha de nacimiento en DD/MM/AAAA + contraseña
     * (mínimo 6 caracteres) en un solo mensaje; si falta algo, dice
     * exactamente qué falta. Si la CURP ya existe con contraseña, se
     * dice y se ofrece continuar con ese paciente.
     */
    private Map<String, Object> responderRegistro(String curpCampo, String texto, String norm) {
        String curp = (curpCampo != null && CurpUtil.esFormatoValido(curpCampo))
                ? CurpUtil.normalizar(curpCampo)
                : CurpUtil.buscarEnTexto(texto).orElse(null);
        if (curp == null) {
            return respuestaSinTramite("Hola, soy Salud Digna. Para ayudarte necesito tu CURP "
                    + "(18 caracteres, por ejemplo GALA900514MDFRPN00). Escríbela en el campo de CURP "
                    + "o en el mensaje y seguimos con tus trámites.",
                    "REGISTRAR_PACIENTE", true, List.of());
        }
        if (esSaludoOAyuda(norm)) {
            return respuestaSinTramite("Hola, soy Salud Digna. No te tengo registrado con la CURP "
                    + curp + ". Para empezar, dime tu nombre completo, tu fecha de nacimiento en formato "
                    + "DD/MM/AAAA y una contraseña de al menos 6 caracteres (por ejemplo: Ana García López "
                    + "14/05/1990 contraseña ana123). Si ya tienes cuenta con otra CURP, escríbela.",
                    "REGISTRAR_PACIENTE", true, List.of());
        }
        Matcher fechaMatcher = buscarFechaExplicita(texto);
        Optional<LocalDate> fechaOpt = extraerFechaExplicita(texto);
        if (fechaMatcher != null && fechaOpt.isEmpty()) {
            return respuestaSinTramite("Esa fecha no existe o no la pude leer. Escríbela en formato DD/MM/AAAA, "
                    + "por ejemplo 14/05/1990, junto con tu nombre completo, tu CURP y tu contraseña.",
                    "REGISTRAR_PACIENTE", true, List.of());
        }
        Optional<String> passwordOpt = extraerPassword(texto);
        String nombre = extraerNombreRegistro(texto, fechaMatcher);
        boolean nombreIncompleto = nombre.isBlank() || !nombre.contains(" ");
        // Qué falta, todo junto: el bot no tiene memoria entre mensajes,
        // así que el registro llega en un solo mensaje y se pide lo que falte.
        List<String> faltantes = new ArrayList<>();
        if (nombreIncompleto) {
            faltantes.add("tu nombre completo (nombre y apellidos)");
        }
        if (fechaOpt.isEmpty()) {
            faltantes.add("tu fecha de nacimiento en formato DD/MM/AAAA");
        }
        if (passwordOpt.isEmpty() || passwordOpt.get().length() < 6) {
            faltantes.add("una contraseña de al menos 6 caracteres (escribe \"contraseña\" seguida de tu contraseña)");
        }
        if (!faltantes.isEmpty()) {
            String ejemplo = "Ana García López GALA900514MDFRPN00 14/05/1990 contraseña ana123";
            return respuestaSinTramite("Me falta " + String.join(", ", faltantes) + ". Escríbelos en un mensaje, "
                    + "por ejemplo: " + ejemplo + ".",
                    "REGISTRAR_PACIENTE", true, List.of());
        }
        // La CURP sí vino: se valida formato, fecha real y entidad.
        Optional<String> motivoCurp = CurpUtil.validar(curp);
        if (motivoCurp.isPresent()) {
            return respuestaSinTramite(motivoCurp.get() + " (" + curp + "). Revisa tu CURP y escríbela de nuevo.",
                    "REGISTRAR_PACIENTE", true, List.of());
        }
        LocalDate fecha = fechaOpt.get();
        if (fecha.isAfter(LocalDate.now()) || fecha.getYear() < 1900) {
            return respuestaSinTramite("Esa fecha no puede ser tu fecha de nacimiento (" + fecha.format(FORMATO_LATINO) + "). "
                    + "Revisa el día, el mes y el año.",
                    "REGISTRAR_PACIENTE", true, List.of());
        }
        LocalDate fechaCurp = CurpUtil.fechaDesdeCurp(curp);
        if (fechaCurp != null && !fechaCurp.equals(fecha)) {
            return respuestaSinTramite("Tu fecha de nacimiento (" + fecha.format(FORMATO_LATINO)
                    + ") no coincide con la fecha de tu CURP (" + fechaCurp.format(FORMATO_LATINO) + "). "
                    + "Revisa la CURP o la fecha.",
                    "REGISTRAR_PACIENTE", true, List.of());
        }
        Paciente nuevo;
        try {
            Paciente alta = new Paciente(nombre, null, null, fecha);
            alta.setCurp(curp);
            alta.setPasswordHash(passwords.hash(passwordOpt.get()));
            nuevo = pacientes.registrar(alta);
        } catch (ResponseStatusException e) {
            // Carrera: la CURP se registró entre la búsqueda y el alta.
            // Si ya existe por CURP, se sigue con ese paciente en vez
            // de dejarlo bloqueado.
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                Optional<Paciente> existente = pacientes.buscarPorCurp(curp);
                if (existente.isPresent()) {
                    Paciente p = existente.get();
                    return respuestaSinTramite("Esa CURP ya está registrada a nombre de " + p.getNombre()
                            + ". Ya te reconocí: dime en qué te ayudo (por ejemplo, \"mis folios\" o \"quiero cotizar un estudio\").",
                            "AYUDA", true, List.of("Ver mis folios", "Iniciar un trámite nuevo"), curp);
                }
                throw e;
            }
            throw e;
        }
        String primerNombre = nuevo.getNombre().split(" ")[0];
        return respuestaSinTramite("Listo " + primerNombre + ", ya quedaste registrado con tu CURP " + nuevo.getCurp()
                + ". ¿Qué estudio necesitas?\n" + textoEstudios(), "ELEGIR_ESTUDIO", true, opcionesEstudios(), nuevo.getCurp());
    }

    /** Contraseña en el mensaje: "contraseña ana123", "clave: x", "password=...". Vacío si no viene. */
    private static Optional<String> extraerPassword(String texto) {
        if (texto == null) {
            return Optional.empty();
        }
        Matcher m = Pattern.compile("(?i)(?:contrase[ñn]a|clave|password)\\s*[:=]?\\s*(\\S+)").matcher(texto);
        if (m.find()) {
            return Optional.of(m.group(1));
        }
        return Optional.empty();
    }

    /**
     * Quita la fecha, la CURP y la contraseña del mensaje y deja el
     * nombre; quita muletillas ("me llamo", "soy", "mi nombre es").
     */
    private String extraerNombreRegistro(String texto, Matcher fechaMatcher) {
        String resto;
        if (fechaMatcher == null) {
            resto = texto;
        } else {
            resto = (texto.substring(0, fechaMatcher.start()) + " " + texto.substring(fechaMatcher.end())).trim();
        }
        Optional<String> curpEnResto = CurpUtil.buscarEnTexto(resto);
        if (curpEnResto.isPresent()) {
            resto = resto.replaceAll("(?i)" + Pattern.quote(curpEnResto.get()), " ");
        }
        resto = resto.replaceAll("(?i)(contrase[ñn]a|clave|password)\\s*[:=]?\\s*\\S+", " ");
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
            nuevo = tramites.cotizar(nuevo.getId(), estudio.get().getId(), "WHATSAPP");
            eventos.registrar(nuevo.getId(), TipoEvento.CANAL_CAMBIADO, "WHATSAPP", "PACIENTE",
                    "{\"mensaje\":\"" + escapar(texto) + "\"}");
            return respuestaCotizado(paciente, nuevo, estudio.get());
        }
        if (esSaludoOAyuda(norm) || quiereCotizar(norm)) {
            String resp = "Hola " + nombre + ", soy Salud Digna 👋. Aún no tienes trámites activos. Puedo cotizarte un estudio:\n"
                    + textoEstudios();
            return respuestaSinTramite(resp, "ELEGIR_ESTUDIO", true, opcionesEstudios(), paciente.getCurp());
        }
        String resp = "No entendí \"" + texto + "\", " + nombre + ". Aún no tienes trámites activos. Puedo cotizarte un estudio:\n"
                + textoEstudios();
        return respuestaSinTramite(resp, "ELEGIR_ESTUDIO", false, opcionesEstudios(), paciente.getCurp());
    }

    /** Varios folios y el mensaje no dice con cuál seguir: se listan y se pregunta, sin elegir al azar. */
    private Map<String, Object> respuestaListaFolios(Paciente paciente, List<Tramite> activos, String texto, String norm) {
        return respuestaListaFolios(paciente, activos, texto, norm, null);
    }

    /** Como la anterior, con prefacio fijo (p. ej. al "volver"): siempre entendido=true. */
    private Map<String, Object> respuestaListaFolios(Paciente paciente, List<Tramite> activos, String texto, String norm, String prefacioFijo) {
        String prefacio;
        boolean entendido;
        if (prefacioFijo != null) {
            prefacio = prefacioFijo;
            entendido = true;
        } else if (esSaludoOAyuda(norm) || esPedirLista(norm)) {
            prefacio = "Hola " + paciente.getNombre().split(" ")[0] + ", tienes " + activos.size() + " trámites activos. ¿Con cuál seguimos?";
            entendido = true;
        } else if (motivoAmbiguo(norm, texto, activos).isPresent()) {
            // La intención sí se reconoció (elegir clínica, una fecha...),
            // lo que falta es saber a qué folio aplica: es una aclaración,
            // no un "no entendí".
            prefacio = "No sé a cuál de tus folios te refieres con ese mensaje: " + motivoAmbiguo(norm, texto, activos).get()
                    + ". Dime el folio con el que seguimos.";
            entendido = true;
        } else {
            prefacio = "No entendí \"" + texto + "\". Tienes " + activos.size() + " trámites activos: dime con cuál seguimos.";
            entendido = false;
        }
        Map<String, Object> respuesta = respuestaSinTramite(prefacio + "\n" + listarFoliosTexto(activos),
                "ELEGIR_FOLIO", entendido, foliosComoOpciones(activos));
        respuesta.put("tramites", activos);
        respuesta.put("curp", paciente.getCurp());
        return respuesta;
    }

    /** Por qué el mensaje es ambiguo entre varios folios (p. ej. dos COTIZADO y el usuario escribió "1"). */
    private Optional<String> motivoAmbiguo(String norm, String texto, List<Tramite> activos) {
        // El mensaje nombra DOS estudios distintos: hay que aclarar
        // cuál, no cotizar el primero que aparezca.
        List<Estudio> mencionados = estudiosMencionados(norm);
        if (mencionados.size() > 1) {
            return Optional.of("mencionaste dos estudios (" + mencionados.get(0).getNombre()
                    + " y " + mencionados.get(1).getNombre() + "); dime solo uno");
        }
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
        // CURP del paciente resuelto: el front la guarda y la reenvía
        // en cada mensaje siguiente (memoria de CURP entre mensajes).
        if (tramite.getPaciente() != null && tramite.getPaciente().getCurp() != null) {
            respuesta.put("curp", tramite.getPaciente().getCurp());
        }
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
        // Nivel que el front reenvía en el siguiente mensaje: la
        // selección por número se resuelve contra la última lista
        // ofrecida en ese nivel (estudios, folios o menú).
        respuesta.put("nivel", siguientePaso);
        respuesta.put("mensaje", mensaje);
        respuesta.put("respuesta", mensaje);
        respuesta.put("opciones", opciones);
        respuesta.put("entendido", entendido);
        respuesta.put("tramites", List.of());
        return respuesta;
    }

    /** Como la de 4 argumentos, pero incluye la CURP del paciente resuelto para que el front la recuerde. */
    private Map<String, Object> respuestaSinTramite(String mensaje, String siguientePaso, boolean entendido,
                                                     List<String> opciones, String curp) {
        Map<String, Object> respuesta = respuestaSinTramite(mensaje, siguientePaso, entendido, opciones);
        if (curp != null && !curp.isBlank()) {
            respuesta.put("curp", curp);
        }
        return respuesta;
    }

    /** El usuario pidió olvidar la CURP ("cambiar de CURP", "volver" sin folio): el front limpia su estado. */
    private Map<String, Object> respuestaOlvidaCurp() {
        Map<String, Object> respuesta = respuestaSinTramite(
                "Claro, olvidé tu CURP. Escríbela de nuevo (18 caracteres, por ejemplo GALA900514MDFRPN00) y seguimos con tus trámites.",
                "REGISTRAR_PACIENTE", true, List.of());
        respuesta.put("olvidarCurp", true);
        return respuesta;
    }

    /**
     * Menú tras identificar por CURP: confirma con entendido=true y
     * da las dos opciones (ver los folios o iniciar un trámite nuevo).
     * NO lista los folios directo: la lista llega solo si el usuario
     * elige "Ver mis folios".
     */
    private Map<String, Object> respuestaMenu(Paciente paciente, List<Tramite> activos) {
        String nombre = paciente.getNombre().split(" ")[0];
        Map<String, Object> respuesta;
        if (activos.isEmpty()) {
            respuesta = respuestaSinTramite("Perfecto, " + nombre + ", te reconocí por tu CURP. Aún no tienes trámites activos. ¿Qué estudio necesitas?\n" + textoEstudios(),
                    "ELEGIR_ESTUDIO", true, opcionesEstudios(), paciente.getCurp());
        } else {
            respuesta = respuestaSinTramite("Perfecto, " + nombre + ", te reconocí por tu CURP. ¿Qué quieres hacer?\n"
                    + listarOpcionesNumeradas(List.of("Ver mis folios", "Iniciar un trámite nuevo")),
                    "ELEGIR_ACCION", true, List.of("Ver mis folios", "Iniciar un trámite nuevo"), paciente.getCurp());
        }
        // El menú no sigue ningún folio: el front limpia el folio en
        // contexto para que la opción "2" se lea como opción de menú y
        // no como opción del folio anterior (captura Eduardo, 2026-11-24:
        // el front conservaba el folio confirmado y "2" llegaba con
        // contexto, resolviéndose como "Ayuda" de la cita confirmada).
        respuesta.put("limpiarFolio", true);
        return respuesta;
    }

    /**
     * Índice (1-based) de la lista que pide el mensaje, o -1 si no pide
     * ninguno: "folio 2", "opción 1", "número 3", "primer folio",
     * "el segundo", "último". Un número suelto ("1", "2") SIN folio en
     * contexto es opción de menú (se resuelve antes, arriba) o, desde
     * el 3, el folio N de la lista; CON folio en contexto NO se usa:
     * en un trámite COTIZADO "1" significa "clínica 1". Con nivel
     * ELEGIR_FOLIO (la lista ofrecida es la de folios) el número solo
     * sí es el folio N, también 1 y 2: el bloque de menú no lo
     * intercepta en ese nivel.
     */
    private int indicePedido(String texto, String norm, boolean conContexto, int total) {
        Matcher m = SELECCION_PATTERN.matcher(texto);
        if (m.find()) {
            // "folio N" selecciona un folio siempre. "opcion N" /
            // "numero N" solo selecciona un folio SIN contexto: con
            // folio en contexto es la opción N del nivel actual
            // (clínica, hora o estudio) y lo resuelve procesarIntencion.
            String palabra = m.group(1).toLowerCase();
            if (palabra.startsWith("folio") || !conContexto) {
                try {
                    return Integer.parseInt(m.group(2));
                } catch (NumberFormatException e) {
                    // sigue a los ordinales
                }
            }
        }
        // Ordinal junto a la palabra "folio" ("primer folio",
        // "el segundo folio"): explícito, manda siempre.
        // Sin "folio": un ordinal femenino ("la primera") solo elige
        // folio si NO hay contexto (con contexto es la clínica 1);
        // los masculinos ("primero", "segundo", "último") eligen
        // folio siempre.
        Integer ord = norm.contains("folio") ? indiceOrdinal(norm, true) : indiceOrdinal(norm, !conContexto);
        if (ord == null) {
            // Sin folio en contexto, un número solo (3, 4...) es el folio
            // N de la lista: el comentario de arriba lo prometía pero
            // este método no lo resolvía (caía en "No entendí"). Con
            // contexto no: ahí un número solo es la opción del nivel
            // actual (clínica, hora, estudio o cita confirmada).
            if (!conContexto) {
                Optional<Integer> solo = numeroSolo(norm);
                if (solo.isPresent()) return solo.get();
            }
            return -1;
        }
        if (ord == Integer.MAX_VALUE) return total; // último
        return ord;
    }

    /** Ordinal en palabras -> número (1..5); "último/a" -> MAX_VALUE (el último de la lista). */
    private Integer indiceOrdinal(String norm, boolean incluirFemenino) {
        Matcher m = ORDINAL_MASC.matcher(norm);
        if (m.find()) return indiceDeOrdinal(m.group(1));
        if (incluirFemenino) {
            Matcher f = ORDINAL_FEM.matcher(norm);
            if (f.find()) return indiceDeOrdinal(f.group(1));
        }
        return null;
    }

    private int indiceDeOrdinal(String palabra) {
        return switch (palabra) {
            case "primer", "primero", "primera" -> 1;
            case "segundo", "segunda" -> 2;
            case "tercero", "tercera" -> 3;
            case "cuarto", "cuarta" -> 4;
            case "quinto", "quinta" -> 5;
            default -> Integer.MAX_VALUE; // ultimo / ultima
        };
    }

    /** "opcion N" / "numero N" y nada más ("opcion 1", "numero 2"): el índice pedido (1-based). */
    private static Optional<Integer> seleccionOpcionPura(String norm) {
        Matcher m = Pattern.compile("^(?:opcion|numero)\\s*(\\d{1,2})\\s*[.,;:!]*$").matcher(norm.trim());
        if (!m.find()) return Optional.empty();
        try {
            return Optional.of(Integer.parseInt(m.group(1)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Un número solo ("1", "2"): el índice pedido (1-based). */
    private static Optional<Integer> numeroSolo(String norm) {
        String t = norm.trim().replaceAll("[.,;:!]+$", "");
        if (!t.matches("\\d{1,2}")) return Optional.empty();
        try {
            return Optional.of(Integer.parseInt(t));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * La opción N de la lista que el bot acaba de ofrecer EN ESTE NIVEL
     * (procesarIntencion, con folio en contexto): clínica N en COTIZADO,
     * hora/fecha N en CLINICA_SELECCIONADA, estudio N en INICIADO /
     * ATENDIDO / cerrados, "¿Cuándo es mi cita?" (1), "Ayuda" (2),
     * "Ver mis folios" (3) o "Iniciar un trámite nuevo" (4) en
     * CONFIRMADO. Vacío si N está fuera de rango (cae al "no entendí",
     * que lista las opciones).
     */
    private Optional<ResultadoBot> resolverSeleccionOpcion(Paciente paciente, Tramite tramite, int n) {
        return switch (tramite.getEstado()) {
            case COTIZADO -> {
                List<Clinica> lista = clinicas.findAll();
                if (n < 1 || n > lista.size()) yield Optional.empty();
                yield Optional.of(clinicaElegida(paciente, tramite, lista.get(n - 1)));
            }
            case CLINICA_SELECCIONADA -> resolverSeleccionFechaHora(paciente, tramite, n);
            case INICIADO, DATOS_PENDIENTES -> {
                List<Estudio> lista = estudios.findAll();
                if (n < 1 || n > lista.size()) yield Optional.empty();
                yield Optional.of(cotizarEstudio(paciente, tramite, lista.get(n - 1)));
            }
            case CONFIRMADO -> {
                if (n == 1) yield Optional.of(new ResultadoBot(resumenEstado(paciente, tramite)
                        + "\nAhora puedes:\n" + listarOpcionesNumeradas(opcionesPara(tramite)),
                        true, opcionesPara(tramite), tramite));
                if (n == 2) yield Optional.of(ayuda(paciente, tramite));
                if (n == 3) {
                    List<Tramite> activos = tramites.listarActivosDe(paciente.getId());
                    yield Optional.of(new ResultadoBot("Claro, " + paciente.getNombre().split(" ")[0]
                            + ", tienes " + activos.size() + " trámites activos. ¿Con cuál seguimos?\n"
                            + listarFoliosTexto(activos), true, foliosComoOpciones(activos), tramite));
                }
                if (n == 4) yield Optional.of(new ResultadoBot("Perfecto, " + paciente.getNombre().split(" ")[0]
                        + ", ¿qué estudio necesitas?\n" + textoEstudios(), true, opcionesEstudios(), tramite,
                        // Se suelta el folio confirmado: es un trámite
                        // NUEVO, y la siguiente respuesta (número o nombre
                        // del estudio) se resuelve sin contexto, contra
                        // la lista de estudios ("6" = estudio 6).
                        true));
                yield Optional.empty();
            }
            case ATENDIDO -> {
                List<Estudio> lista = estudios.findAll();
                if (n < 1 || n > lista.size()) yield Optional.empty();
                yield nuevoTramiteParaEstudio(paciente, tramite, lista.get(n - 1),
                        "Tu trámite " + tramite.getFolio() + " ya fue atendido.");
            }
            case NO_PRESENTADO, NO_REALIZADO -> {
                List<Estudio> lista = estudios.findAll();
                if (n < 1 || n > lista.size()) yield Optional.empty();
                yield nuevoTramiteParaEstudio(paciente, tramite, lista.get(n - 1),
                        "Tu trámite " + tramite.getFolio() + " ya se cerró (" + descripcionEstado(tramite) + ").");
            }
        };
    }

    /**
     * La opción N en CLINICA_SELECCIONADA: si lo último que el bot
     * ofreció fueron las horas libres de un día, la opción N es la hora
     * N libre y la última es "Cambiar clínica"; si no, las opciones son
     * Hoy (1) / Mañana (2) / fecha (3) / "Cambiar clínica" (4), como en
     * opcionesFechas(). "Lo último ofrecido" se sabe por el log: el
     * último evento que no es CANAL_CAMBIADO (el del mensaje actual)
     * es HORAS_OFRECIDAS solo si el bot ofreció horas por último.
     */
    private Optional<ResultadoBot> resolverSeleccionFechaHora(Paciente paciente, Tramite tramite, int n) {
        if (horasOfrecidasPorUltimo(tramite.getId())) {
            Optional<LocalDate> fechaOfrecida = fechaOfrecidaReciente(tramite.getId());
            if (fechaOfrecida.isPresent()) {
                List<String> libres = tramites.horasLibresDelDia(tramite.getClinicaId(), fechaOfrecida.get(),
                        tramite.getEstudioId(), tramite.getId());
                if (n >= 1 && n <= libres.size()) {
                    LocalTime hora = LocalTime.parse(libres.get(n - 1), FORMATO_HORA);
                    return Optional.of(confirmarFechaHora(paciente, tramite,
                            LocalDateTime.of(fechaOfrecida.get(), hora)));
                }
                if (n == libres.size() + 1) {
                    return Optional.of(volverAElegirClinica(paciente, tramite));
                }
                return Optional.empty();
            }
        }
        if (n == 1) return Optional.of(ofrecerHorasLibres(paciente, tramite, LocalDate.now()));
        if (n == 2 || n == 3) return Optional.of(ofrecerHorasLibres(paciente, tramite, LocalDate.now().plusDays(1)));
        if (n == 4) return Optional.of(volverAElegirClinica(paciente, tramite));
        return Optional.empty();
    }

    /** ¿Lo último que el bot ofreció en este trámite fueron horas libres?
     * Se ignora CANAL_CAMBIADO (es el evento del mensaje actual). */
    private boolean horasOfrecidasPorUltimo(UUID tramiteId) {
        List<mx.saluddigna.omnicanal.eventos.domain.Evento> timeline = eventos.listarTimeline(tramiteId);
        for (int i = timeline.size() - 1; i >= 0; i--) {
            String tipo = timeline.get(i).getTipo();
            if (TipoEvento.CANAL_CAMBIADO.equals(tipo)) continue;
            return TipoEvento.HORAS_OFRECIDAS.equals(tipo);
        }
        return false;
    }

    /** El mensaje habla de CONSULTAR/retomar un estudio ("quiero
     * continuar/seguir mi ultrasonido", "ver mi estudio"): no es crear
     * uno nuevo, aunque nombre el estudio. */
    private boolean esConsultarEstudio(String norm) {
        return norm.contains("continuar") || norm.contains("seguir") || norm.contains("retomar")
                || norm.contains("ver mi") || norm.contains("revisar") || norm.contains("consultar")
                || norm.contains("como va") || norm.contains("cuando") || norm.contains("donde")
                || norm.contains("que tengo") || norm.contains("estado de") || norm.contains("mi cita");
    }

    /** Estudios mencionados en el mensaje (por nombre completo o por
     * prefijo): para aclarar cuando nombra dos distintos. */
    private List<Estudio> estudiosMencionados(String norm) {
        List<Estudio> mencionados = new ArrayList<>();
        for (Estudio e : estudios.findAll()) {
            String n = normalizar(e.getNombre());
            if (n.isBlank()) continue;
            if (norm.contains(n) || coincidePorPrefijo(norm, n)) mencionados.add(e);
        }
        return mencionados;
    }

    /**
     * Tolerancia a errores de dedo / nombres recortados: una palabra del
     * mensaje (de 4 letras o más) que sea prefijo de una palabra del
     * nombre del catálogo ("ultra" -> "ultrasonido", "pueb" -> "puebla"),
     * o al revés. Las palabras cortas no cuentan ("ana" no es prefijo
     * válido: podría ser el nombre del paciente).
     */
    private static boolean coincidePorPrefijo(String norm, String nombre) {
        for (String palabra : norm.split("\\s+")) {
            if (palabra.length() < 4) continue;
            for (String parte : nombre.split("\\s+")) {
                if (parte.length() >= 4 && (parte.startsWith(palabra) || palabra.startsWith(parte))) return true;
            }
        }
        return false;
    }

    /** Quita del mensaje los trozos que seleccionan un folio, para
     * procesar solo lo que queda ("folio 1, la de Puebla" -> "la de Puebla"). */
    private String quitarSeleccion(String texto) {
        String s = normalizar(texto);
        s = FOLIO_PATTERN.matcher(s).replaceAll(" ");
        s = SELECCION_PATTERN.matcher(s).replaceAll(" ");
        s = ORDINAL_MASC.matcher(s).replaceAll(" ");
        s = ORDINAL_FEM.matcher(s).replaceAll(" ");
        s = s.replaceAll("\\b(el|la|los|las|folio|opcion|numero)\\b", " ");
        s = s.replaceAll("[,;:.]+", " ").replaceAll("\\s+", " ").trim();
        return s;
    }

    /** El usuario pidió un folio que no tiene ("folio 9", "quinto" con
     * 4 folios): la intención se entendió, lo que no existe es ese
     * número. Se listan los que sí tiene. */
    private Map<String, Object> respuestaFueraDeRango(Paciente paciente, List<Tramite> activos, int pedido) {
        String msg = "No tienes un folio " + pedido + ": solo tienes " + activos.size()
                + " trámite" + (activos.size() == 1 ? "" : "s") + " activo" + (activos.size() == 1 ? "" : "s")
                + ". ¿Con cuál seguimos?\n" + listarFoliosTexto(activos);
        Map<String, Object> respuesta = respuestaSinTramite(msg, "ELEGIR_FOLIO", true, foliosComoOpciones(activos));
        respuesta.put("tramites", activos);
        respuesta.put("curp", paciente.getCurp());
        return respuesta;
    }

    private String listarFoliosTexto(List<Tramite> activos) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < activos.size(); i++) {
            Tramite t = activos.get(i);
            String estudio = t.getEstudioId() == null ? "sin estudio aún"
                    : estudios.findById(t.getEstudioId()).map(Estudio::getNombre).orElse("estudio");
            sb.append(i + 1).append(") ").append(t.getFolio()).append(" — ").append(estudio)
                    .append(" (").append(etiquetaEstado(t)).append("): ").append(descripcionCortaRetomar(t)).append("\n");
        }
        sb.append("Responde con el folio (por ejemplo ").append(activos.get(0).getFolio())
                .append("), con 'folio 1', 'folio 2', etc., o con 'primer folio', 'segundo', etc.");
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
    private ResultadoBot procesarIntencion(Paciente paciente, Tramite tramite, String mensajeCrudo, boolean folioSeleccionado) {
        String norm = normalizar(mensajeCrudo);
        String texto = mensajeCrudo == null ? "" : mensajeCrudo.trim();

        // El mensaje seleccionó un folio ("folio 1", "primer folio",
        // "SD-2026-0012"): eso YA es la intención cumplida. Si no trae
        // nada más, se confirma el folio (entendido=true, nunca
        // "No entendí"); si trae algo más ("folio 1, la de Puebla"),
        // se procesa solo ese resto.
        if (folioSeleccionado) {
            String resto = quitarSeleccion(texto);
            if (resto.isBlank()) {
                return confirmacionSeleccion(paciente, tramite);
            }
            texto = resto;
            norm = normalizar(resto);
        }

        // Cancelar no está disponible por WhatsApp en ningún estado.
        if (norm.contains("cancelar")) {
            return new ResultadoBot("Por WhatsApp no puedo cancelar citas ni trámites, " + paciente.getNombre().split(" ")[0]
                    + ". Para cancelar, llama al Call Center y un asesor lo hace por ti con tu folio " + tramite.getFolio() + ".",
                    true, opcionesPara(tramite), tramite);
        }

        // Una afirmación ("sí", "dale", "va", "ok") nunca es un
        // "No entendí": lo que hace depende del estado (en
        // CLINICA_SELECCIONADA pide la fecha; en COTIZADO lista las
        // clínicas; en el resto, resume el estado con sus opciones).
        if (esAfirmacion(norm)) {
            return switch (tramite.getEstado()) {
                case CLINICA_SELECCIONADA -> new ResultadoBot("¡Perfecto! ¿Qué día prefieres para tu cita en "
                        + nombreClinica(tramite) + "? Dime la fecha en formato DD/MM/AAAA (por ejemplo " + manana()
                        + "), o dime \"hoy\" o \"mañana\", y te muestro las horas libres de ese día.", true, opcionesFechas(), tramite);
                case COTIZADO -> new ResultadoBot(listarClinicasTexto(paciente.getNombre().split(" ")[0], tramite),
                        true, opcionesClinicas(), tramite);
                default -> new ResultadoBot(resumenEstado(paciente, tramite)
                        + "\nAhora puedes:\n" + listarOpcionesNumeradas(opcionesPara(tramite)),
                        true, opcionesPara(tramite), tramite);
            };
        }

        // Selección explícita de las opciones que el bot acaba de ofrecer:
        // "opcion 1" / "numero 2" siempre es la opción N de la lista de
        // ESTE nivel (clínicas en COTIZADO, horas/fechas en
        // CLINICA_SELECCIONADA, estudios en INICIADO, etc.). Un número
        // solo también elige la opción N en los niveles donde no
        // significa otra cosa (estudios, cita confirmada, cerrados); en
        // COTIZADO "1" ya es la clínica 1 y en CLINICA_SELECCIONADA un
        // número solo es una hora: ahí no se toca.
        Optional<Integer> sel = seleccionOpcionPura(norm);
        if (sel.isEmpty() && (tramite.getEstado() == EstadoTramite.INICIADO
                || tramite.getEstado() == EstadoTramite.DATOS_PENDIENTES
                || tramite.getEstado() == EstadoTramite.CONFIRMADO
                || tramite.getEstado() == EstadoTramite.ATENDIDO
                || tramite.getEstado() == EstadoTramite.NO_PRESENTADO
                || tramite.getEstado() == EstadoTramite.NO_REALIZADO)) {
            sel = numeroSolo(norm);
        }
        if (sel.isPresent()) {
            Optional<ResultadoBot> porSeleccion = resolverSeleccionOpcion(paciente, tramite, sel.get());
            if (porSeleccion.isPresent()) return porSeleccion.get();
            // Fuera de rango: cae al "no entendí", que lista las opciones.
        }

        // Nombrar un estudio DISTINTO al del folio en contexto = pedir un
        // trámite NUEVO para ese estudio (el folio actual ya tiene el
        // suyo): se chequea duplicado y se crea o se retoma, como en
        // ATENDIDO y en los cerrados. Si nombra el MISMO estudio, es
        // continuar con este folio (se re-ofrecen sus opciones). La
        // acción del paso actual manda: una clínica en COTIZADO o una
        // fecha en CLINICA_SELECCIONADA no es un estudio nuevo aunque
        // el mensaje también lo mencione.
        String nombre = paciente.getNombre().split(" ")[0];
        Optional<Estudio> estudioNombrado = elegirEstudio(norm);
        if (estudioNombrado.isPresent() && tramite.getEstudioId() != null
                && (tramite.getEstado() == EstadoTramite.COTIZADO
                        || tramite.getEstado() == EstadoTramite.CLINICA_SELECCIONADA
                        || tramite.getEstado() == EstadoTramite.CONFIRMADO)) {
            boolean accionActual = switch (tramite.getEstado()) {
                case COTIZADO -> elegirClinica(norm).isPresent();
                case CLINICA_SELECCIONADA -> pareceFechaHora(texto) || norm.contains("cambiar clinica");
                case CONFIRMADO -> norm.contains("cita") || norm.contains("cuando") || norm.contains("donde")
                        || norm.contains("fecha") || norm.contains("hora");
                default -> false;
            };
            if (!accionActual) {
                if (estudioNombrado.get().getId().equals(tramite.getEstudioId())) {
                    // Mismo estudio: continuar con este folio.
                    if (tramite.getEstado() == EstadoTramite.COTIZADO) {
                        return new ResultadoBot(listarClinicasTexto(nombre, tramite),
                                true, opcionesClinicas(), tramite);
                    }
                    if (tramite.getEstado() == EstadoTramite.CLINICA_SELECCIONADA) {
                        return new ResultadoBot("¡Perfecto! ¿Qué día prefieres para tu cita en "
                                + nombreClinica(tramite) + "? Dime la fecha en formato DD/MM/AAAA (por ejemplo " + manana()
                                + "), o dime \"hoy\" o \"mañana\", y te muestro las horas libres de ese día.",
                                true, opcionesFechas(), tramite);
                    }
                    return new ResultadoBot(resumenEstado(paciente, tramite), true, List.of("Ayuda"), tramite);
                }
                Optional<ResultadoBot> nuevo = nuevoTramiteParaEstudio(paciente, tramite, estudioNombrado.get(),
                        "Tu trámite " + tramite.getFolio() + " ya es de " + nombreEstudio(tramite) + ".");
                if (nuevo.isPresent()) return nuevo.get();
            }
        }

        Optional<ResultadoBot> accion = switch (tramite.getEstado()) {
            case COTIZADO -> procesarCotizado(paciente, tramite, norm);
            case CLINICA_SELECCIONADA -> procesarClinicaSeleccionada(paciente, tramite, texto, norm);
            case INICIADO, DATOS_PENDIENTES -> procesarInicio(paciente, tramite, norm);
            case CONFIRMADO -> procesarConfirmado(paciente, tramite, norm);
            case ATENDIDO -> procesarAtendido(paciente, tramite, norm);
            case NO_PRESENTADO, NO_REALIZADO -> procesarCerrado(paciente, tramite, norm);
        };
        if (accion.isPresent()) return accion.get();
        if (esSaludoOAyuda(norm)) return ayuda(paciente, tramite);
        if (norm.contains("gracias") || norm.contains("adios") || norm.contains("chao") || norm.contains("hasta luego")) {
            return new ResultadoBot("¡De nada, " + paciente.getNombre().split(" ")[0] + "! " + resumenEstado(paciente, tramite),
                    true, opcionesPara(tramite), tramite);
        }
        // Pregunta por su propio trámite ("¿cuándo es mi cita?", "¿cuál
        // es mi clínica?", "¿qué estudio tengo?"): se responde con el
        // estado real, no es un "no entendí".
        if (esPreguntaEstado(norm)) {
            return new ResultadoBot(resumenEstado(paciente, tramite)
                    + "\nAhora puedes:\n" + listarOpcionesNumeradas(opcionesPara(tramite)),
                    true, opcionesPara(tramite), tramite);
        }
        return noEntendi(paciente, tramite, texto);
    }

    private Optional<ResultadoBot> procesarCotizado(Paciente paciente, Tramite tramite, String norm) {
        String nombre = paciente.getNombre().split(" ")[0];
        if (norm.contains("otro estudio") || norm.contains("nuevo estudio")
                || norm.contains("cambiar estudio") || norm.contains("otro tramite")
                || norm.contains("nuevo tramite")) {
            return Optional.of(respuestaOtroEstudio(paciente, tramite));
        }
        Optional<Clinica> elegida = elegirClinica(norm);
        if (elegida.isPresent()) {
            return Optional.of(clinicaElegida(paciente, tramite, elegida.get()));
        }
        // "quiero" solo vale si es querer seguir con la clínica: un
        // "quiero una pizza" no es querer elegir clínica y debe caer
        // en "no entendí", no listar clínicas como si se hubiera entendido.
        if (norm.equals("si") || norm.contains("clinica") || norm.contains("elegir") || norm.equals("quiero")
                || norm.contains("quiero elegir") || norm.contains("quiero la") || norm.contains("quiero clinica")
                || norm.contains("quiero continuar") || norm.contains("quiero seguir")
                || norm.contains("continuar") || norm.contains("adelante")) {
            return Optional.of(new ResultadoBot(listarClinicasTexto(nombre, tramite), true, opcionesClinicas(), tramite));
        }
        return Optional.empty();
    }

    private Optional<ResultadoBot> procesarClinicaSeleccionada(Paciente paciente, Tramite tramite, String mensajeCrudo, String norm) {
        if (norm.contains("otro estudio") || norm.contains("nuevo estudio")
                || norm.contains("cambiar estudio") || norm.contains("otro tramite")
                || norm.contains("nuevo tramite")) {
            return Optional.of(respuestaOtroEstudio(paciente, tramite));
        }
        if (norm.contains("cambiar clinica") || norm.contains("cambiar la clinica") || norm.contains("otra clinica")) {
            return Optional.of(volverAElegirClinica(paciente, tramite));
        }
        // Fecha con hora opcional ("25/11/2026 10:00", "25/11 a las 10",
        // "el 25", "el 25 de noviembre", "mañana"). Sin hora NO se asume
        // las 10:00: se ofrecen las horas libres de ese día (parrilla),
        // para que el usuario elija una libre.
        Optional<LocalDateTime> fechaHoraOpt = extraerFechaHora(mensajeCrudo);
        if (fechaHoraOpt.isPresent()) {
            LocalDateTime fechaHora = fechaHoraOpt.get();
            if (extraerHora(mensajeCrudo).isEmpty()) {
                // Solo fecha, sin hora: se ofrecen las horas libres de ese
                // día. El "pasado" solo se valida con hora real (abajo):
                // "hoy" a las 15:00 no es pasado, quedan horas libres.
                if (fechaHora.toLocalDate().isBefore(LocalDate.now())) {
                    return Optional.of(new ResultadoBot("Ese día ya pasó (" + fechaHora.toLocalDate().format(FORMATO_LATINO)
                            + "). Dime otro día en formato DD/MM/AAAA, o dime \"hoy\" o \"mañana\".",
                            true, opcionesFechas(), tramite));
                }
                return Optional.of(ofrecerHorasLibres(paciente, tramite, fechaHora.toLocalDate()));
            }
            return Optional.of(confirmarFechaHora(paciente, tramite, fechaHora));
        }
        // Solo la hora, sin fecha ("10", "10:00", "a las 10", "10 am"):
        // se usa el día que el bot ofreció por último (queda guardado
        // en el log como evento horas.ofrecidas). Sin día ofrecido, se
        // pide la fecha con ejemplo, no un "formato no válido".
        Optional<LocalTime> horaSola = extraerHora(mensajeCrudo);
        if (horaSola.isPresent()) {
            Optional<LocalDate> fechaOfrecida = fechaOfrecidaReciente(tramite.getId());
            if (fechaOfrecida.isPresent()) {
                return Optional.of(confirmarFechaHora(paciente, tramite,
                        LocalDateTime.of(fechaOfrecida.get(), horaSola.get())));
            }
            return Optional.of(new ResultadoBot("¿Para qué día quieres la cita a las "
                    + horaSola.get().format(FORMATO_HORA) + "? Dime la fecha en formato DD/MM/AAAA "
                    + "(por ejemplo " + manana() + " " + horaSola.get().format(FORMATO_HORA)
                    + "), o dime \"hoy\" o \"mañana\".",
                    true, opcionesFechas(), tramite));
        }
        if (pareceFechaHora(mensajeCrudo)) {
            return Optional.of(new ResultadoBot("Esa fecha u hora no existe o no la pude leer (" + mensajeCrudo.trim() + "). "
                    + "Escríbela en formato DD/MM/AAAA con hora, por ejemplo " + manana() + " 10:00, "
                    + "o dime solo la hora (por ejemplo 10:00) si ya te ofrecí las horas de un día, "
                    + "o dime \"hoy\" o \"mañana\".",
                    true, opcionesFechas(), tramite));
        }
        return Optional.empty();
    }

    /**
     * Confirma la cita en la fecha+hora dadas: pasado (400) se dice
     * claro; ocupada (409) pasa el mensaje del backend con las horas
     * libres cercanas; hora fuera de la parrilla (400) se responde con
     * las horas libres cercanas de ese día (antes era solo el mensaje
     * de la parrilla, sin alternativas). Éxito: confirmación con folio,
     * fecha/hora y clínica.
     */
    private ResultadoBot confirmarFechaHora(Paciente paciente, Tramite tramite, LocalDateTime fechaHora) {
        String nombre = paciente.getNombre().split(" ")[0];
        if (fechaHora.isBefore(LocalDateTime.now())) {
            return new ResultadoBot("Esa fecha y hora ya pasaron (" + fechaHora.format(FORMATO_LATINO_HORA)
                    + " es anterior a ahora). Dime otro día y hora para tu cita.",
                    true, opcionesFechas(), tramite);
        }
        Tramite actualizado;
        try {
            actualizado = tramites.confirmar(tramite.getId(), fechaHora, "WHATSAPP");
        } catch (ResponseStatusException e) {
            String motivo = e.getReason() != null ? e.getReason() : e.getMessage();
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                // confirmar ya trae las horas libres más cercanas
                // en el mensaje (409); se pasa tal cual.
                return new ResultadoBot(motivo + " Dime otra fecha u hora.", true, opcionesFechas(), tramite);
            }
            if (e.getStatusCode() == HttpStatus.BAD_REQUEST && motivo != null && motivo.contains("parrilla")) {
                List<String> cercanas = tramites.horasLibresCercanas(tramite.getClinicaId(), fechaHora.toLocalDate(),
                        fechaHora.toLocalTime(), tramite.getEstudioId(), tramite.getId());
                String detalle = cercanas.isEmpty()
                        ? "Ese día ya no quedan horas libres en " + nombreClinica(tramite) + "."
                        : "Horas libres cercanas ese día: " + String.join(", ", cercanas) + ".";
                return new ResultadoBot(motivo + " " + detalle + " Dime otra hora.", true, opcionesFechas(), tramite);
            }
            return new ResultadoBot(motivo, true, opcionesFechas(), tramite);
        }
        return new ResultadoBot("Confirmado " + nombre + ". Tu cita del trámite " + actualizado.getFolio()
                + " quedó para el " + fechaHora.format(FORMATO_LATINO_HORA) + " en " + nombreClinica(actualizado) + ". Te esperamos.",
                true, List.of("¿Cuándo es mi cita?", "Ayuda"), actualizado);
    }

    /**
     * Último día para el que el bot ofreció horas libres en este
     * trámite (evento horas.ofrecidas del log, el más reciente): así
     * una hora suelta después ("10", "10:00") se entiende para ese
     * día. Vacío si nunca se ofrecieron horas en este trámite.
     */
    private Optional<LocalDate> fechaOfrecidaReciente(UUID tramiteId) {
        List<mx.saluddigna.omnicanal.eventos.domain.Evento> timeline = eventos.listarTimeline(tramiteId);
        for (int i = timeline.size() - 1; i >= 0; i--) {
            mx.saluddigna.omnicanal.eventos.domain.Evento ev = timeline.get(i);
            if (!TipoEvento.HORAS_OFRECIDAS.equals(ev.getTipo())) continue;
            String payload = ev.getPayload() == null ? "" : ev.getPayload();
            Matcher m = Pattern.compile("\"fecha\"\\s*:\\s*\"(\\d{4}-\\d{2}-\\d{2})\"").matcher(payload);
            if (m.find()) {
                try {
                    return Optional.of(LocalDate.parse(m.group(1)));
                } catch (java.time.DateTimeException e) {
                    return Optional.empty();
                }
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * Horas libres de la parrilla de un día para el trámite en contexto
     * (clínica ya elegida, estudio del trámite). Las opciones llevan la
     * fecha y la hora juntas ("25/11/2026 10:00") para que al pulsarlas
     * el bot pueda confirmar sin tener que adivinar el día.
     */
    private ResultadoBot ofrecerHorasLibres(Paciente paciente, Tramite tramite, LocalDate fecha) {
        List<TramiteService.ParrillaSlot> slots =
                tramites.parrilla(tramite.getClinicaId(), fecha, tramite.getEstudioId(), tramite.getId());
        List<String> libres = slots.stream().filter(s -> !s.ocupado()).map(TramiteService.ParrillaSlot::hora).toList();
        String fechaTexto = fecha.format(FORMATO_LATINO);
        if (libres.isEmpty()) {
            return new ResultadoBot("Ese día (" + fechaTexto + ") no quedan horas libres en " + nombreClinica(tramite)
                    + ". Dime otro día en formato DD/MM/AAAA, o dime \"mañana\".",
                    true, opcionesFechas(), tramite);
        }
        // El día ofrecido queda en el log: si el usuario responde solo
        // con la hora ("10", "10:00"), se entiende para este día.
        eventos.registrar(tramite.getId(), TipoEvento.HORAS_OFRECIDAS, "WHATSAPP", "SISTEMA",
                "{\"fecha\":\"" + fecha + "\"}");
        String fechaHoraEjemplo = fechaTexto + " " + libres.get(0);
        List<String> opciones = new ArrayList<>();
        for (String hora : libres) {
            opciones.add(fechaTexto + " " + hora);
        }
        opciones.add("Cambiar clínica");
        return new ResultadoBot("El " + fechaTexto + " en " + nombreClinica(tramite)
                + " tengo libres: " + String.join(", ", libres)
                + ". ¿A qué hora te viene bien? Responde con la fecha y la hora, por ejemplo " + fechaHoraEjemplo + ".",
                true, opciones, tramite);
    }

    /**
     * Retomar en vez de duplicar: si el paciente ya tiene un trámite
     * ACTIVO de ese estudio, se responde para seguir con ese folio
     * (con lo que le falta: clínica / fecha / hora) en vez de crear
     * otro. La respuesta lleva el trámite existente, así que el front
     * lo toma como folio en contexto y los mensajes siguientes ya van
     * sobre él. Si el usuario dice "sí", la afirmación continúa con
     * ese folio (COTIZADO → elegir clínica; CLINICA_SELECCIONADA →
     * pedir fecha). Vacío si no hay duplicado: se sigue el flujo
     * normal (cotizar / crear). Los terminales no cuentan: un estudio
     * ya atendido o cerrado sí se puede volver a cotizar.
     */
    private Optional<ResultadoBot> respuestaRetomarSiDuplicado(Paciente paciente, Estudio estudio, UUID exceptoTramiteId) {
        Optional<Tramite> existente = tramites.buscarActivoPorEstudio(paciente.getId(), estudio.getId(), exceptoTramiteId);
        if (existente.isEmpty()) return Optional.empty();
        Tramite t = existente.get();
        List<String> opciones = new ArrayList<>(opcionesPara(t));
        opciones.add("Ver mis folios");
        // El mensaje es el resumen compartido con Web/Asesor: si está
        // CONFIRMADO, la cita ya está agendada y se da toda la
        // información, sin ofrecer continuar el flujo; si falta algo,
        // se pregunta si quiere retomarlo con lo que falta.
        return Optional.of(new ResultadoBot(textoRetomar(t), true, opciones, t));
    }

    /** Texto de retomar para un trámite: cita ya agendada (CONFIRMADO, con toda la información) o qué falta para completarlo. */
    private String textoRetomar(Tramite t) {
        Map<String, Object> resumen = tramites.resumenRetomar(t);
        if (Boolean.TRUE.equals(resumen.get("yaAgendada"))) {
            return (String) resumen.get("mensaje");
        }
        List<String> faltantes = TramiteService.faltantes(t);
        String faltaTexto = faltantes.isEmpty()
                ? "tu cita ya está confirmada"
                : "te falta: " + textoFaltantes(faltantes);
        return (String) resumen.get("mensaje") + " Seguimos con ese folio: " + faltaTexto + ".";
    }

    /** Una línea de lo que falta (o la cita agendada) para listar varios trámites. */
    private String descripcionCortaRetomar(Tramite t) {
        if (t.getEstado() == EstadoTramite.CONFIRMADO) {
            return "cita agendada el " + (t.getFechaCita() == null ? "sin fecha" : t.getFechaCita().format(FORMATO_LATINO_HORA))
                    + " en " + nombreClinica(t);
        }
        List<String> faltantes = TramiteService.faltantes(t);
        return faltantes.isEmpty() ? "listo para atender" : "te falta: " + textoFaltantes(faltantes);
    }

    /** Texto humano de lo que le falta a un trámite ("cotizar el estudio", "elegir clínica", "confirmar la cita"). */
    private static String textoFaltantes(List<String> faltantes) {
        List<String> partes = new ArrayList<>();
        if (faltantes.contains("cotizado")) partes.add("cotizar el estudio");
        if (faltantes.contains("clinica")) partes.add("elegir clínica");
        if (faltantes.contains("fecha") || faltantes.contains("hora")) partes.add("confirmar la cita (fecha y hora)");
        return String.join(" y ", partes);
    }

    /**
     * Cotiza un estudio en el trámite en contexto (INICIADO /
     * DATOS_PENDIENTES): primero el chequeo de duplicado (retomar en
     * vez de duplicar) y solo si no hay duplicado se cotiza. Red de
     * seguridad del bug de WhatsApp: si se retoma y el trámite en
     * contexto era un folio vacío (sin estudio), se borra para que no
     * quede un folio huérfano en Historial; el retomar sigue sobre el
     * folio EXISTENTE (excluido de la búsqueda por exceptoTramiteId).
     */
    private ResultadoBot cotizarEstudio(Paciente paciente, Tramite tramite, Estudio estudio) {
        Optional<ResultadoBot> retomar = respuestaRetomarSiDuplicado(paciente, estudio, tramite.getId());
        if (retomar.isPresent()) {
            if (tramite.getEstudioId() == null) {
                tramites.eliminarTramiteVacio(tramite.getId());
            }
            return retomar.get();
        }
        Tramite actualizado = tramites.cotizar(tramite.getId(), estudio.getId(), "WHATSAPP");
        return new ResultadoBot("Hola " + paciente.getNombre().split(" ")[0] + ", tu trámite " + actualizado.getFolio()
                + " ya está cotizado: " + estudio.getNombre()
                + " por $" + actualizado.getPrecioCotizado()
                + ". Ahora elige clínica.", true, opcionesClinicas(), actualizado);
    }

    /**
     * Trámite NUEVO para un estudio cuando el folio en contexto ya tiene
     * otro estudio o ya se cerró: primero el chequeo de duplicado (si ya
     * hay un activo de ese estudio se retoma ese folio, no se crea otro)
     * y solo si no hay duplicado se crea CON estudio y se cotiza en el
     * mismo paso. `prefijo` es la frase del folio en contexto ("Tu
     * trámite X ya fue atendido." / "Tu trámite X ya es de Y.").
     */
    private Optional<ResultadoBot> nuevoTramiteParaEstudio(Paciente paciente, Tramite tramiteEnContexto,
                                                           Estudio estudio, String prefijo) {
        Optional<ResultadoBot> retomar = respuestaRetomarSiDuplicado(paciente, estudio,
                tramiteEnContexto == null ? null : tramiteEnContexto.getId());
        if (retomar.isPresent()) return retomar;
        Tramite nuevo = tramites.crearTramite(paciente.getId(), estudio.getId(), "WHATSAPP");
        nuevo = tramites.cotizar(nuevo.getId(), estudio.getId(), "WHATSAPP");
        return Optional.of(new ResultadoBot(prefijo + " Te creé uno nuevo: "
                + nuevo.getFolio() + " — " + estudio.getNombre() + " por $" + nuevo.getPrecioCotizado()
                + ". Ahora elige clínica.", true, opcionesClinicas(), nuevo));
    }

    /** Respuesta de un trámite recién cotizado: folio, estudio, precio y a elegir clínica. */
    private Map<String, Object> respuestaCotizado(Paciente paciente, Tramite cotizado, Estudio estudio) {
        return respuestaConTramite(cotizado, tramites.listarActivosDe(paciente.getId()),
                new ResultadoBot("Hola " + paciente.getNombre().split(" ")[0] + ", tu trámite " + cotizado.getFolio()
                        + " ya está cotizado: " + estudio.getNombre() + " por $" + cotizado.getPrecioCotizado()
                        + ". Ahora elige clínica.", true, opcionesClinicas(), cotizado));
    }

    /** Estudio elegido sin folio en contexto (por nombre o por número
     * de la lista ofrecida): PRIMERO el chequeo de duplicado, nunca
     * crear antes. Si ya hay un activo de ese estudio se retoma ese
     * folio (y si había un único folio vacío huérfano, se borra); si
     * no hay duplicado y hay un único trámite vacío pendiente se
     * reutiliza (no se crea otro folio); si no, se crea CON estudio
     * y se cotiza en el mismo paso, como responderSinFolios. */
    private Map<String, Object> respuestaEstudioElegido(Paciente paciente, Estudio estudio) {
        Optional<ResultadoBot> retomar = respuestaRetomarSiDuplicado(paciente, estudio, null);
        if (retomar.isPresent()) {
            List<Tramite> vacios = vaciosSinEstudio(tramites.listarActivosDe(paciente.getId()));
            if (vacios.size() == 1) {
                tramites.eliminarTramiteVacio(vacios.get(0).getId());
            }
            return respuestaConTramite(retomar.get().tramite(),
                    tramites.listarActivosDe(paciente.getId()), retomar.get());
        }
        List<Tramite> vacios = vaciosSinEstudio(tramites.listarActivosDe(paciente.getId()));
        if (vacios.size() == 1) {
            Tramite cotizado = tramites.cotizar(vacios.get(0).getId(), estudio.getId(), "WHATSAPP");
            return respuestaCotizado(paciente, cotizado, estudio);
        }
        Tramite nuevo = tramites.crearTramite(paciente.getId(), estudio.getId(), "WHATSAPP");
        nuevo = tramites.cotizar(nuevo.getId(), estudio.getId(), "WHATSAPP");
        return respuestaCotizado(paciente, nuevo, estudio);
    }

    /** "¿Qué estudio necesitas?" sin crear ningún trámite: el trámite se
     * crea solo al nombrar el estudio y pasar el duplicado. */
    private Map<String, Object> respuestaPreguntarEstudio(Paciente paciente) {
        Map<String, Object> respuesta = respuestaSinTramite("Perfecto, " + paciente.getNombre().split(" ")[0]
                + ", ¿qué estudio necesitas?\n" + textoEstudios(),
                "ELEGIR_ESTUDIO", true, opcionesEstudios(), paciente.getCurp());
        // Se empieza un trámite nuevo: el front limpia el folio en
        // contexto para que el estudio elegido no se lea contra el
        // folio anterior (misma causa que el menú, captura Eduardo).
        respuesta.put("limpiarFolio", true);
        return respuesta;
    }

    /** Elige una clínica para el trámite en contexto (COTIZADO). */
    private ResultadoBot clinicaElegida(Paciente paciente, Tramite tramite, Clinica elegida) {
        Tramite actualizado = tramites.seleccionarClinica(tramite.getId(), elegida.getId(), "WHATSAPP");
        String resp = "Listo " + paciente.getNombre().split(" ")[0] + ", elegiste " + elegida.getNombre()
                + " para tu trámite " + actualizado.getFolio()
                + ". Ahora dime qué día prefieres para tu cita, en formato DD/MM/AAAA (por ejemplo " + manana()
                + "), y te muestro las horas libres de ese día.";
        return new ResultadoBot(resp, true, opcionesFechas(), actualizado);
    }

    /** Vuelve a elegir clínica (de CLINICA_SELECCIONADA a COTIZADO): lo
     * usan "cambiar clínica", "opcion N" = "Cambiar clínica" y
     * "regresar" un nivel atrás. No crea folio nuevo. */
    private ResultadoBot volverAElegirClinica(Paciente paciente, Tramite tramite) {
        Tramite actualizado = tramites.volverACotizado(tramite.getId(), "WHATSAPP");
        return new ResultadoBot("Sin problema, " + paciente.getNombre().split(" ")[0] + ". Volvemos a elegir clínica para tu trámite "
                + actualizado.getFolio() + ":\n" + listarClinicasTexto(paciente.getNombre().split(" ")[0], actualizado),
                true, opcionesClinicas(), actualizado);
    }

    /** "quiero otro estudio" con un folio que ya tiene estudio: no se
     * deshace la cotización; se pregunta cuál otro estudio quiere y el
     * siguiente mensaje (con el estudio nombrado) crea o retoma su trámite. */
    private ResultadoBot respuestaOtroEstudio(Paciente paciente, Tramite tramite) {
        return new ResultadoBot("Tu trámite " + tramite.getFolio() + " ya es de " + nombreEstudio(tramite)
                + (tramite.getPrecioCotizado() == null ? "" : " por $" + tramite.getPrecioCotizado())
                + ". Si quieres otro estudio, dime cuál necesitas:\n" + textoEstudios(),
                true, opcionesEstudios(), tramite);
    }

    /** Trámites activos vacíos (sin estudio) del paciente: los folios
     * huérfanos que dejó el bug de WhatsApp. */
    private static List<Tramite> vaciosSinEstudio(List<Tramite> activos) {
        return activos.stream()
                .filter(t -> t.getEstudioId() == null
                        && (t.getEstado() == EstadoTramite.INICIADO || t.getEstado() == EstadoTramite.DATOS_PENDIENTES))
                .toList();
    }

    /**
     * "regresar" / "atrás": un nivel atrás SIN deshacer datos.
     * - CLINICA_SELECCIONADA: vuelve a elegir clínica (volverACotizado).
     * - COTIZADO: re-muestra la cotización (estudio y precio, el paso
     *   anterior) y se sigue eligiendo clínica.
     * - CONFIRMADO: la cita sigue confirmada (NO se des-confirma nada
     *   ni se pretende retroceder un paso): se re-muestra el resumen de
     *   la cita con las opciones útiles de ese nivel.
     * - INICIADO / DATOS_PENDIENTES / cerrados, o sin folio en contexto:
     *   menú principal (el nivel anterior a elegir estudio es el menú).
     */
    private Map<String, Object> respuestaRegresarNivel(Paciente paciente, List<Tramite> activos, String folioContexto) {
        if (folioContexto == null || folioContexto.isBlank()) {
            return respuestaMenu(paciente, activos);
        }
        Tramite tramite;
        try {
            tramite = tramites.buscarPorFolio(folioContexto.trim().toUpperCase());
        } catch (ResponseStatusException e) {
            return respuestaMenu(paciente, activos);
        }
        if (!tramite.getPaciente().getId().equals(paciente.getId())) {
            return respuestaMenu(paciente, activos);
        }
        String nombre = paciente.getNombre().split(" ")[0];
        return switch (tramite.getEstado()) {
            case CLINICA_SELECCIONADA -> {
                ResultadoBot r = volverAElegirClinica(paciente, tramite);
                yield respuestaConTramite(r.tramite(), tramites.listarActivosDe(paciente.getId()), r);
            }
            case COTIZADO -> {
                String texto = "Volvemos un paso atrás, " + nombre + ". Tu trámite " + tramite.getFolio()
                        + " ya está cotizado: " + nombreEstudio(tramite)
                        + (tramite.getPrecioCotizado() == null ? "" : " por $" + tramite.getPrecioCotizado())
                        + ". Sigues eligiendo clínica:\n" + listarClinicasTexto(nombre, tramite);
                yield respuestaConTramite(tramite, activos,
                        new ResultadoBot(texto, true, opcionesClinicas(), tramite));
            }
            case CONFIRMADO -> {
                // La cita sigue confirmada: "regresar" aquí NO retrocede
                // ningún paso (no se des-confirma nada), así que no se
                // dice "volvemos un paso atrás" (captura María,
                // 2026-11-24: ese texto mentía, nada había cambiado). Se
                // muestra el resumen y las opciones útiles de este nivel
                // (cuándo es la cita, ver folios, trámite nuevo, ayuda).
                String texto = "Tu cita sigue confirmada, " + nombre + ". " + resumenEstado(paciente, tramite)
                        + "\nAhora puedes:\n" + listarOpcionesNumeradas(opcionesPara(tramite));
                yield respuestaConTramite(tramite, activos,
                        new ResultadoBot(texto, true, opcionesPara(tramite), tramite));
            }
            default -> respuestaMenu(paciente, activos);
        };
    }

    private Optional<ResultadoBot> procesarInicio(Paciente paciente, Tramite tramite, String norm) {
        String nombre = paciente.getNombre().split(" ")[0];
        Optional<Estudio> estudio = elegirEstudio(norm);
        if (estudio.isPresent()) {
            return Optional.of(cotizarEstudio(paciente, tramite, estudio.get()));
        }
        if (quiereCotizar(norm)) {
            return Optional.of(new ResultadoBot("Claro " + nombre + ", estos son nuestros estudios:\n" + textoEstudios(),
                    true, opcionesEstudios(), tramite));
        }
        return Optional.empty();
    }

    private Optional<ResultadoBot> procesarConfirmado(Paciente paciente, Tramite tramite, String norm) {
        String nombre = paciente.getNombre().split(" ")[0];
        if (norm.contains("otro estudio") || norm.contains("nuevo estudio")
                || norm.contains("cambiar estudio") || norm.contains("otro tramite")
                || norm.contains("nuevo tramite")) {
            return Optional.of(respuestaOtroEstudio(paciente, tramite));
        }
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
            // El trámite en contexto ya se cerró, pero puede haber
            // OTRO trámite activo del mismo estudio: entonces se
            // retoma ese en vez de crear uno nuevo.
            return nuevoTramiteParaEstudio(paciente, tramite, estudio.get(),
                    "Tu trámite " + tramite.getFolio() + " ya fue atendido.");
        }
        if (quiereCotizar(norm)) {
            return Optional.of(new ResultadoBot("Claro " + nombre + ", estos son nuestros estudios:\n" + textoEstudios(),
                    true, opcionesEstudios(), tramite));
        }
        return Optional.empty();
    }

    /** Trámite cerrado (NO_PRESENTADO / NO_REALIZADO): como ATENDIDO,
     * se ofrece cotizar otro estudio; nunca se reabre el cerrado. */
    private Optional<ResultadoBot> procesarCerrado(Paciente paciente, Tramite tramite, String norm) {
        String nombre = paciente.getNombre().split(" ")[0];
        Optional<Estudio> estudio = elegirEstudio(norm);
        if (estudio.isPresent()) {
            // Como en procesarAtendido: el folio en contexto está
            // cerrado, pero puede haber OTRO trámite activo del mismo
            // estudio y ese se retoma en vez de crear uno nuevo.
            return nuevoTramiteParaEstudio(paciente, tramite, estudio.get(),
                    "Tu trámite " + tramite.getFolio() + " ya se cerró (" + descripcionEstado(tramite) + ").");
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
                    .append(". Puedo confirmar tu cita: dime el día en formato DD/MM/AAAA, o dime \"hoy\" o \"mañana\", "
                            + "y te muestro las horas libres de ese día para que elijas una.");
            case INICIADO, DATOS_PENDIENTES -> sb.append("Puedo cotizarte un estudio: dime cuál necesitas (ultrasonido, perfil tiroideo, radiografía, análisis de sangre, electrocardiograma o mastografía).");
            case CONFIRMADO -> sb.append("Tu cita ya está confirmada. Puedo decirte cuándo y dónde es.");
            case ATENDIDO -> sb.append("Tu trámite ya fue atendido. Puedo cotizarte otro estudio.");
            case NO_PRESENTADO -> sb.append("Tu trámite ").append(tramite.getFolio())
                    .append(" ya se cerró porque no te presentaste a tu cita. Puedo cotizarte otro estudio.");
            case NO_REALIZADO -> sb.append("Tu trámite ").append(tramite.getFolio())
                    .append(" ya se cerró porque no se pudo realizar el estudio. Puedo cotizarte otro estudio.");
        }
        sb.append("\n").append(listarOpcionesNumeradas(opcionesPara(tramite)));
        sb.append("\nResponde con el nombre de la opción, con su número o con \"opcion 1\" / \"numero 2\".");
        return new ResultadoBot(sb.toString(), true, opcionesPara(tramite), tramite);
    }

    /** Entrada no reconocida: lo dice claro (esa opción no existe en
     * este paso), muestra el estado y las opciones válidas ahora, con
     * el número de cada una. Nunca inventa un avance. El mensaje queda
     * en el log como mensaje.no_entendido con el texto crudo. */
    private ResultadoBot noEntendi(Paciente paciente, Tramite tramite, String mensajeCrudo) {
        List<String> opciones = opcionesPara(tramite);
        String crudo = mensajeCrudo == null ? "" : mensajeCrudo.trim();
        eventos.registrar(tramite.getId(), TipoEvento.MENSAJE_NO_ENTENDIDO, "WHATSAPP", "PACIENTE",
                "{\"mensaje\":\"" + escapar(crudo) + "\"}");
        String texto = "No entendí \"" + crudo + "\": esa opción no existe en este paso. " + resumenEstado(paciente, tramite)
                + "\nAhora puedes:\n" + listarOpcionesNumeradas(opciones)
                + "\nResponde con el nombre de la opción, con su número (1, 2, 3) o con \"opcion 1\" / \"numero 2\".";
        return new ResultadoBot(texto, false, opciones, tramite);
    }

    /** Resumen en una línea del folio: estudio, precio, estado y qué falta. */
    private String resumenEstado(Paciente paciente, Tramite tramite) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tu trámite ").append(tramite.getFolio()).append(" (").append(nombreEstudio(tramite));
        if (tramite.getPrecioCotizado() != null) sb.append(" $").append(tramite.getPrecioCotizado());
        sb.append("): ");
        sb.append(descripcionEstado(tramite));
        sb.append(".");
        return sb.toString();
    }

    /** Qué significa el estado actual y qué falta (sin el folio ni el estudio). */
    private String descripcionEstado(Tramite tramite) {
        return switch (tramite.getEstado()) {
            case INICIADO -> "aún no tiene estudio elegido";
            case DATOS_PENDIENTES -> "le faltan datos por completar";
            case COTIZADO -> "ya está cotizado, falta elegir clínica";
            case CLINICA_SELECCIONADA -> nombreClinica(tramite) + ", falta confirmar la fecha";
            case CONFIRMADO -> "cita el "
                    + (tramite.getFechaCita() == null ? "sin fecha" : tramite.getFechaCita().format(FORMATO_LATINO_HORA))
                    + " en " + nombreClinica(tramite);
            case ATENDIDO -> "ya fue atendido";
            case NO_PRESENTADO -> "no se presentó a su cita";
            case NO_REALIZADO -> "no se pudo realizar el estudio";
        };
    }

    /** Confirmación de que se seleccionó un folio: nunca un "No entendí". */
    private ResultadoBot confirmacionSeleccion(Paciente paciente, Tramite tramite) {
        List<String> opciones = opcionesPara(tramite);
        String desc = descripcionEstado(tramite);
        desc = Character.toUpperCase(desc.charAt(0)) + desc.substring(1);
        String resp = "Perfecto, seguimos con tu trámite " + tramite.getFolio()
                + " (" + nombreEstudio(tramite)
                + (tramite.getPrecioCotizado() != null ? " $" + tramite.getPrecioCotizado() : "") + "). "
                + desc
                + "\nAhora puedes:\n" + listarOpcionesNumeradas(opciones);
        return new ResultadoBot(resp, true, opciones, tramite);
    }

    private String nombreEstudio(Tramite tramite) {
        if (tramite.getEstudioId() == null) return "sin estudio aún";
        return estudios.findById(tramite.getEstudioId()).map(Estudio::getNombre).orElse("estudio");
    }

    private String nombreClinica(Tramite tramite) {
        if (tramite.getClinicaId() == null) return "sin clínica";
        return clinicas.findById(tramite.getClinicaId()).map(Clinica::getNombre).orElse("clínica");
    }

    /**
     * Etiqueta legible del estado de un trámite para mostrarle al
     * usuario: nunca el nombre técnico del enum. Con clínica elegida
     * se muestra el nombre de la clínica.
     */
    private String etiquetaEstado(Tramite t) {
        return TramiteService.etiquetaEstado(t.getEstado(),
                t.getClinicaId() == null ? null : nombreClinica(t));
    }

    /** Respuestas sugeridas para el estado actual: el front las muestra como botones. */
    private List<String> opcionesPara(Tramite tramite) {
        return switch (tramite.getEstado()) {
            case COTIZADO -> opcionesClinicas();
            case CLINICA_SELECCIONADA -> opcionesFechas();
            case INICIADO, DATOS_PENDIENTES -> opcionesEstudios();
            case CONFIRMADO -> List.of("¿Cuándo es mi cita?", "Ayuda", "Ver mis folios", "Iniciar un trámite nuevo");
            case ATENDIDO -> opcionesEstudios();
            case NO_PRESENTADO, NO_REALIZADO -> opcionesEstudios();
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
        List<Estudio> lista = estudios.findAll();
        List<String> opciones = new ArrayList<>();
        for (int i = 0; i < lista.size(); i++) {
            // Numeradas como las clínicas ("1 · ..."): así "opcion 1" /
            // "numero 2" y un número solo eligen el estudio N.
            opciones.add((i + 1) + " · " + lista.get(i).getNombre() + " $" + lista.get(i).getPrecio().toPlainString());
        }
        return opciones;
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
        for (int i = 0; i < lista.size(); i++) {
            Estudio e = lista.get(i);
            sb.append(i + 1).append(") ").append(e.getNombre()).append(" — $").append(e.getPrecio().toPlainString()).append("\n");
        }
        sb.append("Dime cuál necesitas (por ejemplo \"opcion 1\" o el nombre del estudio).");
        return sb.toString();
    }

    /** Opciones numeradas para el texto. Las que ya traen su número
     * ("1 · Clínica Puebla Centro") se dejan tal cual: antes salía
     * "1) 1 · Clínica Puebla Centro", con el número duplicado. */
    private String listarOpcionesNumeradas(List<String> opciones) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < opciones.size(); i++) {
            String op = opciones.get(i);
            if (op.matches("^\\d+\\s*[·•.)\\-].*")) {
                sb.append(op).append("\n");
            } else {
                sb.append(i + 1).append(") ").append(op).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /** Pide ver la lista de folios ("folio"/"folios" pelados, "mis folios", "muestrame mis folios", "qué folios tengo", "muestrame mi folio"): no es un error, se listan. "folio 1" NO es pedir lista (es selección; lo filtra quien llama). */
    private boolean esPedirLista(String norm) {
        return norm.equals("folio") || norm.equals("folios")
                || norm.contains("mis folios") || norm.contains("mis tramites")
                || norm.contains("lista de folios") || norm.contains("lista de tramites")
                || norm.contains("ver mis folios") || norm.contains("ver mis tramites")
                || norm.contains("muestrame mis folios") || norm.contains("muestrame mis tramites")
                || norm.contains("muestra mis folios") || norm.contains("muestra mis tramites")
                || norm.contains("muestrame mi folio") || norm.contains("muestra mi folio")
                || norm.contains("ensenarme mis folios") || norm.contains("ensenarme mis tramites")
                || norm.contains("ensename mis folios") || norm.contains("ensename mis tramites")
                || norm.contains("mostrar mis folios") || norm.contains("mostrar mis tramites")
                || norm.contains("cuales son mis folios") || norm.contains("cuales son mis tramites")
                || norm.contains("que folios tengo") || norm.contains("que tramites tengo")
                || norm.contains("todos mis folios") || norm.contains("todos mis tramites")
                || norm.equals("seguimiento") || norm.contains("dar seguimiento")
                || norm.contains("seguimiento de mis folios") || norm.contains("seguir mis folios")
                || norm.contains("mis pendientes") || norm.contains("mi agenda") || norm.contains("agendad")
                || norm.contains("cotizaciones") || norm.contains("mi lista") || norm.contains("pendiente")
                || norm.contains("mis citas") || norm.contains("mis reservaciones") || norm.contains("mis reservas")
                || norm.contains("mis estudios") || norm.contains("ver mi agenda")
                || norm.contains("que tengo agendado") || norm.contains("que tengo pendiente")
                || norm.contains("lista de citas") || norm.contains("ver mis citas")
                || norm.equals("agenda");
    }

    /**
     * El mensaje habla de su trámite/estudio/reservación/cita y pide
     * verlo o continuarlo ("quiero continuar mi tramite", "ver mi
     * reservacion", "mi cita", "como va mi estudio"). Tags: tramite,
     * estudio, reservacion, reserva, cita. NO es consultar cuando el
     * mensaje trae una selección de folio, una fecha u hora, un cambio
     * o reprogramación, o pide otro/nuevo estudio (eso es cotizar).
     * Un número suelto ("1", "2") o una hora ("10") nunca es
     * consultar: no traen la palabra del trámite.
     */
    private boolean esConsultarTramite(String norm, String texto) {
        if (!Pattern.compile("\\b(tramite|estudio|reservacion|reserva|cita)\\b").matcher(norm).find()) return false;
        if (SELECCION_PATTERN.matcher(texto).find() || FOLIO_PATTERN.matcher(texto).find()
                || ORDINAL_MASC.matcher(norm).find() || ORDINAL_FEM.matcher(norm).find()) return false;
        if (extraerFechaHora(texto).isPresent() || extraerHora(texto).isPresent()
                || pareceFechaHora(texto) || pareceFecha(texto)) return false;
        if (norm.contains("cambiar") || norm.contains("reprogramar") || norm.contains("mover mi cita")
                || norm.contains("otro estudio") || norm.contains("nuevo estudio")
                || norm.contains("otro tramite") || norm.contains("nuevo tramite")
                || norm.contains("otra cita") || norm.contains("nueva cita")
                || norm.contains("agendar") || norm.contains("reservar")
                || norm.contains("nueva reservacion") || norm.contains("nueva reserva")
                || norm.contains("quiero una cita")) return false;
        // Nombra un estudio concreto sin pedir continuar/verlo: es
        // cotizar ese estudio, no consultar el trámite.
        if (elegirEstudio(norm).isPresent()
                && !norm.contains("continuar") && !norm.contains("seguir") && !norm.contains("retomar")
                && !norm.contains("ver") && !norm.contains("revisar") && !norm.contains("consultar")
                && !norm.contains("como va") && !norm.contains("cuando es") && !norm.contains("donde es")) return false;
        return norm.contains("mi ") || norm.contains("quiero") || norm.contains("continuar")
                || norm.contains("seguir") || norm.contains("retomar") || norm.contains("ver")
                || norm.contains("revisar") || norm.contains("consultar") || norm.contains("como va")
                || norm.contains("cuando") || norm.contains("donde") || norm.contains("cual")
                || norm.contains("tengo")
                || norm.equals("tramite") || norm.equals("estudio") || norm.equals("reservacion")
                || norm.equals("reserva") || norm.equals("cita");
    }

    /**
     * Respuesta a "quiero continuar mi tramite" / "ver mi reservacion"
     * / "mi cita": el resumen del trámite según su estado. Con un solo
     * activo, ese trámite queda en contexto para seguir en él; con
     * varios, se listan con su estado y lo que les falta; sin activos,
     * se ofrecen los estudios. Un CONFIRMADO se responde con la cita ya
     * agendada y toda su información, no con "¿quieres retomarlo?".
     */
    private Map<String, Object> respuestaConsultarTramite(Paciente paciente, List<Tramite> activos, String folioContexto) {
        String nombre = paciente.getNombre().split(" ")[0];
        if (activos.isEmpty()) {
            return respuestaSinTramite("Hola " + nombre + ", aún no tienes trámites activos. Puedo cotizarte un estudio:\n" + textoEstudios(),
                    "ELEGIR_ESTUDIO", true, opcionesEstudios(), paciente.getCurp());
        }
        // Con folio en contexto, se consulta ese trámite.
        if (folioContexto != null && !folioContexto.isBlank()) {
            try {
                Tramite enContexto = tramites.buscarPorFolio(folioContexto.trim().toUpperCase());
                if (enContexto.getPaciente().getId().equals(paciente.getId())
                        && !TramiteService.ESTADOS_TERMINALES.contains(enContexto.getEstado())) {
                    return respuestaConTramite(enContexto, activos,
                            new ResultadoBot(textoRetomar(enContexto), true, opcionesPara(enContexto), enContexto));
                }
            } catch (ResponseStatusException e) {
                // Folio en contexto inválido: se sigue con la lista.
            }
        }
        if (activos.size() == 1) {
            Tramite t = activos.get(0);
            return respuestaConTramite(t, activos, new ResultadoBot(textoRetomar(t), true, opcionesPara(t), t));
        }
        StringBuilder sb = new StringBuilder("Claro, ").append(nombre).append(", tienes ").append(activos.size())
                .append(" trámites activos:\n");
        for (int i = 0; i < activos.size(); i++) {
            Tramite t = activos.get(i);
            sb.append(i + 1).append(") ").append(t.getFolio()).append(" — ").append(nombreEstudio(t))
                    .append(" (").append(etiquetaEstado(t)).append("): ").append(descripcionCortaRetomar(t)).append("\n");
        }
        sb.append("¿Con cuál seguimos? Responde con el folio, por ejemplo ").append(activos.get(0).getFolio()).append(".");
        Map<String, Object> respuesta = respuestaSinTramite(sb.toString(), "ELEGIR_FOLIO", true, foliosComoOpciones(activos));
        respuesta.put("tramites", activos);
        respuesta.put("curp", paciente.getCurp());
        return respuesta;
    }

    /** Volver / cambiar de folio: olvida el contexto y lista los folios para elegir otro. "cambiar clínica" y "otro estudio" NO son volver; "regresar" / "atrás" tampoco (eso es un nivel atrás, se resuelve antes). */
    private boolean esVolver(String norm) {
        if (norm.contains("otro estudio") || norm.contains("cambiar clinica") || norm.contains("cambiar la clinica")) return false;
        if (norm.contains("cambiar de folio") || norm.contains("cambiar folio")
                || norm.contains("otro folio") || norm.contains("elegir otro")
                || norm.contains("otro tramite") || norm.contains("salir de este folio")
                || norm.contains("salir del folio") || norm.contains("cambiar de tramite")) return true;
        return Pattern.compile("\\bvolver\\b").matcher(norm).find();
    }

    /** "volver al inicio" / "volver al menú" / "ir al menú" / "menú" / "inicio" / "principal":
     * menú principal con la CURP recordada (nunca se olvida la CURP).
     * "inicio" pelado también es ir al menú (captura María, 2026-11-24:
     * antes caía en "No entendí: esa opción no existe en este paso"). */
    private boolean esVolverAlMenu(String norm) {
        return norm.contains("volver al inicio") || norm.contains("volver al menu")
                || norm.contains("regresar al inicio") || norm.contains("regresar al menu")
                || norm.contains("ir al inicio") || norm.contains("ir al menu")
                || norm.contains("al inicio") || norm.contains("al principio")
                || norm.equals("menu") || norm.equals("menu principal") || norm.contains("menu principal")
                || norm.equals("inicio") || norm.equals("principal");
    }

    /** "regresar" / "atrás" / "anterior" / "un paso atrás": un nivel atrás (no cambiar de folio: eso es "volver").
     * "anterior" pelado es regresar (captura Ana, 2026-11-24: "anterior" caía en "No entendí"); "folio
     * anterior" NO es esto (eso sería elegir un folio, no se usa así hoy). "antes" solo vale exacto
     * ("antes" pelado = volver atrás; "quiero mi cita antes" es otra cosa y no entra aquí). */
    private boolean esRegresarNivel(String norm) {
        if (norm.contains("cambiar de folio") || norm.contains("otro folio") || norm.contains("folio anterior")) return false;
        return norm.contains("regresar") || norm.contains("atras") || norm.contains("paso anterior")
                || norm.contains("volver atras") || norm.equals("antes")
                || Pattern.compile("\\banterior\\b").matcher(norm).find();
    }

    /** El usuario quiere olvidar la CURP recordada y empezar de cero (o cambiar de paciente). */
    private boolean esCambiarCurp(String norm) {
        return norm.contains("cambiar de curp") || norm.contains("otra curp") || norm.contains("cambiar de paciente")
                || norm.contains("olvidar mi curp") || norm.contains("olvidar la curp");
    }

    /**
     * El mensaje es solo la identificación por CURP (con saludo o
     * relleno): hay que dar el menú, no la lista ni un "No entendí".
     * Con una selección de folio explícita no es el menú.
     */
    private boolean esMenuInicial(String norm, boolean curpEnMensaje, boolean conContexto, Paciente paciente) {
        if (conContexto) return false;
        if (!curpEnMensaje && !esSaludoOAyuda(norm)) return false;
        String resto = norm.replaceAll("[a-z]{4}\\d{6}[hm][a-z]{2}[a-z]{3}[a-z\\d]\\d", " ");
        resto = resto.replaceAll("cambiar de (la )?curp|otra curp|mi curp es|la curp es|mi curp|hola|buenas|buenos dias|tardes|noches|soy|me llamo|es", " ");
        resto = resto.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        if (!resto.isEmpty()) {
            // El resto es solo el nombre del paciente ("hola, soy Fernanda"): también es el menú.
            String nombreNorm = normalizar(paciente.getNombre());
            String primerNombre = normalizar(paciente.getNombre().split(" ")[0]);
            if (!resto.equals(nombreNorm) && !resto.equals(primerNombre)) return false;
        }
        if (SELECCION_PATTERN.matcher(norm).find() || FOLIO_PATTERN.matcher(norm).find()
                || ORDINAL_MASC.matcher(norm).find() || ORDINAL_FEM.matcher(norm).find()) {
            return false;
        }
        return true;
    }

    /** Petición explícita de un trámite nuevo (botón "Iniciar un trámite nuevo" u equivalente).
     * "nuevo" es la señal distintiva de CREAR: "quiero un nuevo tramite/estudio",
     * "quiero reservar/agendar (un estudio/una cita)", "otra cita". "continuar/seguir"
     * es retomar el activo (esConsultarTramite) y nunca llega aquí. */
    private boolean esNuevoTramiteExplicito(String norm) {
        return norm.contains("iniciar un tramite nuevo") || norm.contains("tramite nuevo")
                || norm.contains("nuevo tramite") || norm.contains("iniciar tramite")
                || norm.contains("nueva reservacion") || norm.contains("hacer una reservacion")
                || norm.contains("quiero reservar") || norm.contains("reservar un estudio")
                || norm.contains("empezar un tramite")
                || norm.contains("nuevo estudio") || norm.contains("otro estudio")
                || norm.contains("quiero un estudio nuevo") || norm.contains("empezar un estudio")
                || norm.contains("quiero agendar") || norm.contains("agendar un estudio")
                || norm.contains("agendar una cita") || norm.contains("quiero una cita")
                || norm.contains("nueva cita") || norm.contains("otra cita")
                || norm.contains("reservar") || norm.contains("quiero una reservacion")
                || norm.contains("nueva reserva") || norm.contains("hacer una reserva")
                || norm.contains("quiero una reserva");
    }

    /** Cotización genérica ("quiero cotizar", "quiero un estudio") sin estudio concreto. */
    private boolean esCotizacionGenerica(String norm) {
        return norm.contains("quiero cotizar") || norm.contains("quiero un estudio")
                || norm.contains("necesito un estudio") || norm.equals("cotizar")
                || norm.contains("quiero un estudio nuevo") || norm.contains("necesito otro estudio")
                || norm.contains("quiero otro estudio") || norm.contains("quiero agendar")
                || norm.contains("quiero una cita") || norm.contains("agendar");
    }

    /** Afirmación ("sí", "confirmo", "dale", "va", "de acuerdo", "ok", "vale", "claro"): nunca es un "no entendí"; lo que hace depende del estado. */
    private boolean esAfirmacion(String norm) {
        return norm.equals("si") || norm.equals("confirmo") || norm.equals("dale") || norm.equals("va")
                || norm.equals("de acuerdo") || norm.equals("ok") || norm.equals("vale") || norm.equals("claro")
                || norm.equals("perfecto")
                || norm.startsWith("si,") || norm.startsWith("si ") || norm.startsWith("claro que");
    }

    /** Pregunta por su propio trámite ("¿cuándo es mi cita?", "¿cuál
     * es mi clínica?", "¿qué estudio tengo?", "¿cuánto cuesta?"): se
     * responde con el estado real, no es un "no entendí". */
    private boolean esPreguntaEstado(String norm) {
        return norm.contains("mi cita") || norm.contains("cuando") || norm.contains("donde")
                || norm.contains("que estudio") || norm.contains("cual es mi") || norm.contains("mi folio")
                || norm.contains("folio") || norm.contains("estado") || norm.contains("que tengo")
                || norm.contains("mi tramite") || norm.contains("que clinica") || norm.contains("cual clinica")
                || norm.contains("mi clinica") || norm.contains("cuanto") || norm.contains("precio")
                || norm.contains("costo");
    }

    private boolean esSaludoOAyuda(String norm) {
        return norm.equals("hola") || norm.startsWith("hola ") || norm.contains("ayuda") || norm.contains("ayudame")
                || norm.contains("opciones") || norm.contains("que puedo hacer") || norm.contains("que puedes hacer")
                || norm.contains("menu") || norm.contains("buenos dias") || norm.contains("buenas tardes")
                || norm.contains("que hago") || norm.contains("como sigo") || norm.contains("no se que hacer")
                || norm.contains("buenas noches") || norm.equals("buenas") || norm.equals("hi") || norm.equals("hello");
    }

    private boolean quiereCotizar(String norm) {
        return norm.contains("cotizar") || norm.contains("cotizacion") || norm.contains("quiero un estudio")
                || norm.contains("quiero cotizar") || norm.contains("empezar") || norm.contains("nuevo tramite")
                || norm.contains("otro estudio") || norm.contains("necesito un estudio") || norm.contains("estudio")
                || norm.contains("agendar") || norm.contains("reservar") || norm.contains("nueva cita")
                || norm.contains("otra cita") || norm.contains("nuevo estudio");
    }

    /**
     * Elegir clínica: "1", "uno", "primera", "la primera", "puebla",
     * "centro", "juarez", "la de puebla", "2", "dos", "segunda",
     * "la segunda", "tehuacan", "la de tehuacan", "quiero la de Puebla"
     * y el nombre de la clínica. Por número se usa el orden del catálogo.
     */
    /**
     * Elige una clínica por lo que escribió el usuario (número, ordinal o
     * nombre). NUNCA elige una por defecto: si el mensaje no nombra una
     * clínica de forma explícita, vacío (el bot lista las clínicas o dice
     * "no entendí"). Bug 2026-11-24: la versión anterior hacía
     * `n.contains(norm)`, así que escribir solo "clínica" (palabra que
     * está en el nombre de todas las clínicas) elegía la primera de la
     * lista, Puebla, sin que el usuario la hubiera elegido; y los
     * `.or(() -> lista.get(0))` hacían lo mismo cuando el nombre no se
     * encontraba. Solo cuentan las palabras distintivas (puebla, centro,
     * tehuacan, juarez): las genéricas ("clínica", "salud", "digna") no
     * eligen ninguna.
     */
    private Optional<Clinica> elegirClinica(String norm) {
        List<Clinica> lista = clinicas.findAll();
        if (lista.isEmpty()) return Optional.empty();
        String token = norm.trim();
        // Por número u ordinal: elección explícita del usuario.
        if (token.equals("1") || token.equals("uno") || norm.contains("primera")
                || norm.contains("la 1") || norm.contains("la uno")) {
            return Optional.of(lista.get(0));
        }
        if (token.equals("2") || token.equals("dos") || norm.contains("segunda")
                || norm.contains("la 2") || norm.contains("la dos")) {
            return lista.size() > 1 ? Optional.of(lista.get(1)) : Optional.empty();
        }
        // "Juárez" es la Av. Juárez de Puebla Centro (dirección, no nombre).
        if (norm.contains("juarez")) {
            return lista.stream()
                    .filter(c -> normalizar(c.getDireccion() == null ? "" : c.getDireccion()).contains("juarez"))
                    .findFirst();
        }
        for (Clinica c : lista) {
            String n = normalizar(c.getNombre());
            if (n.isBlank()) continue;
            // Nombre completo ("clinica puebla centro"): elección explícita.
            if (norm.contains(n)) return Optional.of(c);
            // Palabra distintiva del nombre o la ciudad ("pueb", "tehu",
            // "centro"): una palabra del mensaje (≥4 letras) que sea
            // prefijo de una palabra distintiva, o al revés. Las
            // genéricas no cuentan (ver comentario del método).
            String distintivas = n + " " + normalizar(c.getCiudad() == null ? "" : c.getCiudad());
            for (String palabra : norm.split("\\s+")) {
                if (palabra.length() < 4) continue;
                for (String parte : distintivas.split("\\s+")) {
                    if (parte.length() < 4 || esPalabraGenericaClinica(parte)) continue;
                    if (parte.startsWith(palabra) || palabra.startsWith(parte)) return Optional.of(c);
                }
            }
        }
        return Optional.empty();
    }

    /** Palabras que están en los nombres de las clínicas y por eso no
     * distinguen a ninguna: escribirlas no es elegir clínica. */
    private static boolean esPalabraGenericaClinica(String palabra) {
        return palabra.equals("clinica") || palabra.equals("salud") || palabra.equals("digna")
                || palabra.equals("de") || palabra.equals("la") || palabra.equals("el")
                || palabra.equals("los") || palabra.equals("las");
    }

    private Optional<Estudio> elegirEstudio(String norm) {
        for (Estudio e : estudios.findAll()) {
            String n = normalizar(e.getNombre());
            if (!n.isBlank() && norm.contains(n)) return Optional.of(e);
            // Tolerancia a errores de dedo / nombres recortados ("ultra",
            // "mast", "radi"): una palabra del mensaje que sea prefijo de
            // una palabra del nombre del estudio (o al revés).
            if (!n.isBlank() && coincidePorPrefijo(norm, n)) return Optional.of(e);
            if (norm.contains("ultrasonido") && n.contains("ultrasonido")) return Optional.of(e);
            if ((norm.contains("tiroideo") || norm.contains("tiroides")) && n.contains("tiroideo")) return Optional.of(e);
            if ((norm.contains("tsh") || norm.contains("t3") || norm.contains("t4")) && n.contains("tiroideo")) return Optional.of(e);
            if (norm.contains("radiografia") && n.contains("radiografia")) return Optional.of(e);
            if ((norm.contains("analisis") || norm.contains("sangre") || norm.contains("biometria")) && n.contains("analisis")) return Optional.of(e);
            if ((norm.contains("electrocardiograma") || norm.contains("electro") || norm.contains("ecg") || norm.contains("corazon")) && n.contains("electrocardiograma")) return Optional.of(e);
            if ((norm.contains("mastografia") || norm.contains("mama") || norm.contains("seno")) && n.contains("mastografia")) return Optional.of(e);
        }
        return Optional.empty();
    }

    private static String normalizar(String s) {
        if (s == null) return "";
        String sinAcentos = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinAcentos.toLowerCase().trim().replaceAll("\\s+", " ");
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
