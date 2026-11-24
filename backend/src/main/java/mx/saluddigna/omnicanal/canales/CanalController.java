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
    public record MensajeWhatsappRequest(String curp, @NotBlank String mensaje, String folio) {
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

    /** Fecha corta DD/MM (año actual): "25/11". No casa con DD/MM/AAAA (eso lo cubre FECHA_LATINA). */
    private static final Pattern FECHA_CORTA = Pattern.compile("(\\d{1,2})/(\\d{1,2})(?!/)");

    /** "el 25 de noviembre", "25 de noviembre de 2026". */
    private static final Pattern FECHA_MES_NOMBRE = Pattern.compile(
            "(?:el\\s+)?(\\d{1,2})\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|setiembre|octubre|noviembre|diciembre)(?:\\s+de\\s+(\\d{4}))?",
            Pattern.CASE_INSENSITIVE);

    /** "el 25": día del mes actual. */
    private static final Pattern FECHA_EL_DIA = Pattern.compile("\\bel\\s+(\\d{1,2})\\b", Pattern.CASE_INSENSITIVE);

    /** Hora: "10:00", "a las 10", "10 h". */
    private static final Pattern HORA_HHMM = Pattern.compile("(\\d{1,2}):(\\d{2})\\b");
    private static final Pattern HORA_A_LAS = Pattern.compile("a\\s+las?\\s+(\\d{1,2})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HORA_H = Pattern.compile("(\\d{1,2})\\s*h(?:oras?)?\\b", Pattern.CASE_INSENSITIVE);

    /** Formato en el que se le muestran las fechas al usuario: DD/MM/AAAA. */
    private static final DateTimeFormatter FORMATO_LATINO = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final DateTimeFormatter FORMATO_LATINO_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

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

    /** Hora escrita en el mensaje ("10:00", "a las 10", "10 h"). Vacío si no hay hora o si es imposible (25:00). */
    private static Optional<LocalTime> extraerHora(String mensaje) {
        if (mensaje == null) return Optional.empty();
        Matcher hhmm = HORA_HHMM.matcher(mensaje);
        if (hhmm.find()) {
            try {
                return Optional.of(LocalTime.of(Integer.parseInt(hhmm.group(1)), Integer.parseInt(hhmm.group(2))));
            } catch (java.time.DateTimeException e) {
                return Optional.empty();
            }
        }
        Matcher aLas = HORA_A_LAS.matcher(mensaje);
        if (aLas.find()) {
            try {
                return Optional.of(LocalTime.of(Integer.parseInt(aLas.group(1)), 0));
            } catch (java.time.DateTimeException e) {
                return Optional.empty();
            }
        }
        Matcher h = HORA_H.matcher(mensaje);
        if (h.find()) {
            try {
                return Optional.of(LocalTime.of(Integer.parseInt(h.group(1)), 0));
            } catch (java.time.DateTimeException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** ¿El mensaje trae algo que parece una hora escrita? (para distinguir "hora imposible" de "no era una hora"). */
    private static boolean pareceHora(String mensaje) {
        if (mensaje == null) return false;
        return HORA_HHMM.matcher(mensaje).find() || HORA_A_LAS.matcher(mensaje).find() || HORA_H.matcher(mensaje).find();
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
     */
    private static final Pattern SELECCION_PATTERN =
            Pattern.compile("(?:folio|opci[oó]n|n[uú]mero)\\s*(\\d{1,2})", Pattern.CASE_INSENSITIVE);

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
        if (esVolver(norm)) {
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
                            + ". Escribe tu CURP para continuar con tus trámites.",
                            "REGISTRAR_PACIENTE", true, List.of());
                }
                throw e;
            }
            throw e;
        }
        String primerNombre = nuevo.getNombre().split(" ")[0];
        return respuestaSinTramite("Listo " + primerNombre + ", ya quedaste registrado con tu CURP " + nuevo.getCurp()
                + ". ¿Qué estudio necesitas?\n" + textoEstudios(), "ELEGIR_ESTUDIO", true, opcionesEstudios());
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

    /**
     * Índice (1-based) de la lista que pide el mensaje, o -1 si no pide
     * ninguno: "folio 2", "opción 1", "número 3", "primer folio",
     * "el segundo", "último". Un número suelto ("1", "2") NO se usa:
     * en un trámite COTIZADO "1" significa "clínica 1".
     */
    private int indicePedido(String texto, String norm, boolean conContexto, int total) {
        Matcher m = SELECCION_PATTERN.matcher(texto);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                // sigue a los ordinales
            }
        }
        // Ordinal junto a la palabra "folio" ("primer folio",
        // "el segundo folio"): explícito, manda siempre.
        // Sin "folio": un ordinal femenino ("la primera") solo elige
        // folio si NO hay contexto (con contexto es la clínica 1);
        // los masculinos ("primero", "segundo", "último") eligen
        // folio siempre.
        Integer ord = norm.contains("folio") ? indiceOrdinal(norm, true) : indiceOrdinal(norm, !conContexto);
        if (ord == null) return -1;
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
        return respuesta;
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
        Optional<Clinica> elegida = elegirClinica(norm);
        if (elegida.isPresent()) {
            Tramite actualizado = tramites.seleccionarClinica(tramite.getId(), elegida.get().getId());
            String resp = "Listo " + nombre + ", elegiste " + elegida.get().getNombre()
                    + " para tu trámite " + actualizado.getFolio()
                    + ". Ahora dime qué día prefieres para tu cita, en formato DD/MM/AAAA (por ejemplo " + manana()
                    + "), y te muestro las horas libres de ese día.";
            return Optional.of(new ResultadoBot(resp, true, opcionesFechas(), actualizado));
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
        String nombre = paciente.getNombre().split(" ")[0];
        if (norm.contains("cambiar clinica") || norm.contains("cambiar la clinica") || norm.contains("otra clinica")) {
            Tramite actualizado = tramites.volverACotizado(tramite.getId());
            return Optional.of(new ResultadoBot("Sin problema, " + nombre + ". Volvemos a elegir clínica para tu trámite "
                    + actualizado.getFolio() + ":\n" + listarClinicasTexto(nombre, actualizado),
                    true, opcionesClinicas(), actualizado));
        }
        // Fecha con hora opcional ("25/11/2026 10:00", "25/11 a las 10",
        // "el 25", "el 25 de noviembre", "mañana"). Sin hora NO se asume
        // las 10:00: se ofrecen las horas libres de ese día (parrilla),
        // para que el usuario elija una libre.
        Optional<LocalDateTime> fechaHoraOpt = extraerFechaHora(mensajeCrudo);
        if (fechaHoraOpt.isPresent()) {
            LocalDateTime fechaHora = fechaHoraOpt.get();
            if (fechaHora.isBefore(LocalDateTime.now())) {
                return Optional.of(new ResultadoBot("Esa fecha y hora ya pasaron (" + fechaHora.format(FORMATO_LATINO_HORA)
                        + " es anterior a ahora). Dime otro día y hora para tu cita.",
                        true, opcionesFechas(), tramite));
            }
            if (extraerHora(mensajeCrudo).isEmpty()) {
                return Optional.of(ofrecerHorasLibres(paciente, tramite, fechaHora.toLocalDate()));
            }
            Tramite actualizado;
            try {
                actualizado = tramites.confirmar(tramite.getId(), fechaHora);
            } catch (ResponseStatusException e) {
                if (e.getStatusCode() == HttpStatus.CONFLICT) {
                    // confirmar ya trae las horas libres más cercanas
                    // en el mensaje (409); se pasa tal cual.
                    String motivo = e.getReason() != null ? e.getReason() : e.getMessage();
                    return Optional.of(new ResultadoBot(motivo + " Dime otra fecha u hora.",
                            true, opcionesFechas(), tramite));
                }
                String motivo = e.getReason() != null ? e.getReason() : e.getMessage();
                return Optional.of(new ResultadoBot(motivo, true, opcionesFechas(), tramite));
            }
            return Optional.of(new ResultadoBot("Confirmado " + nombre + ". Tu cita del trámite " + actualizado.getFolio()
                    + " quedó para el " + fechaHora.format(FORMATO_LATINO_HORA) + " en " + nombreClinica(actualizado) + ". Te esperamos.",
                    true, List.of("¿Cuándo es mi cita?", "Ayuda"), actualizado));
        }
        if (pareceFechaHora(mensajeCrudo)) {
            return Optional.of(new ResultadoBot("Esa fecha u hora no existe o no la pude leer (" + mensajeCrudo.trim() + "). "
                    + "Escríbela en formato DD/MM/AAAA con hora, por ejemplo " + manana() + " 10:00, o dime \"hoy\" o \"mañana\".",
                    true, opcionesFechas(), tramite));
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

    /** Trámite cerrado (NO_PRESENTADO / NO_REALIZADO): como ATENDIDO,
     * se ofrece cotizar otro estudio; nunca se reabre el cerrado. */
    private Optional<ResultadoBot> procesarCerrado(Paciente paciente, Tramite tramite, String norm) {
        String nombre = paciente.getNombre().split(" ")[0];
        Optional<Estudio> estudio = elegirEstudio(norm);
        if (estudio.isPresent()) {
            Tramite nuevo = tramites.crearTramite(paciente.getId(), estudio.get().getId(), "WHATSAPP");
            nuevo = tramites.cotizar(nuevo.getId(), estudio.get().getId());
            return Optional.of(new ResultadoBot("Tu trámite " + tramite.getFolio() + " ya se cerró ("
                    + descripcionEstado(tramite) + "). Te creé uno nuevo: "
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
                    .append(". Puedo confirmar tu cita: dime el día en formato DD/MM/AAAA, o dime \"hoy\" o \"mañana\", "
                            + "y te muestro las horas libres de ese día para que elijas una.");
            case INICIADO, DATOS_PENDIENTES -> sb.append("Puedo cotizarte un estudio: dime cuál necesitas (ultrasonido, perfil tiroideo, radiografía o análisis de sangre).");
            case CONFIRMADO -> sb.append("Tu cita ya está confirmada. Puedo decirte cuándo y dónde es.");
            case ATENDIDO -> sb.append("Tu trámite ya fue atendido. Puedo cotizarte otro estudio.");
            case NO_PRESENTADO -> sb.append("Tu trámite ").append(tramite.getFolio())
                    .append(" ya se cerró porque no te presentaste a tu cita. Puedo cotizarte otro estudio.");
            case NO_REALIZADO -> sb.append("Tu trámite ").append(tramite.getFolio())
                    .append(" ya se cerró porque no se pudo realizar el estudio. Puedo cotizarte otro estudio.");
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
            case CLINICA_SELECCIONADA -> "clínica " + nombreClinica(tramite) + ", falta confirmar la fecha";
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
        String resp = "Perfecto, seguimos con tu trámite " + tramite.getFolio()
                + " (" + nombreEstudio(tramite)
                + (tramite.getPrecioCotizado() != null ? " $" + tramite.getPrecioCotizado() : "") + "). "
                + "Está en estado " + tramite.getEstado().name() + ": " + descripcionEstado(tramite)
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

    /** Respuestas sugeridas para el estado actual: el front las muestra como botones. */
    private List<String> opcionesPara(Tramite tramite) {
        return switch (tramite.getEstado()) {
            case COTIZADO -> opcionesClinicas();
            case CLINICA_SELECCIONADA -> opcionesFechas();
            case INICIADO, DATOS_PENDIENTES -> opcionesEstudios();
            case CONFIRMADO -> List.of("¿Cuándo es mi cita?", "Ayuda");
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
                || norm.contains("todos mis folios") || norm.contains("todos mis tramites");
    }

    /** Volver / cambiar de folio: olvida el contexto y lista los folios para elegir otro. "cambiar clínica" y "otro estudio" NO son volver. */
    private boolean esVolver(String norm) {
        if (norm.contains("otro estudio") || norm.contains("cambiar clinica") || norm.contains("cambiar la clinica")) return false;
        if (norm.contains("cambiar de folio") || norm.contains("cambiar folio")
                || norm.contains("otro folio") || norm.contains("elegir otro")
                || norm.contains("otro tramite") || norm.contains("salir de este folio")
                || norm.contains("salir del folio") || norm.contains("cambiar de tramite")) return true;
        return Pattern.compile("\\b(volver|regresar|atras)\\b").matcher(norm).find();
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
            if ((norm.contains("tsh") || norm.contains("t3") || norm.contains("t4")) && n.contains("tiroideo")) return Optional.of(e);
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
