package mx.saluddigna.omnicanal.config;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import mx.saluddigna.omnicanal.catalogo.ClinicaRepository;
import mx.saluddigna.omnicanal.catalogo.EstudioRepository;
import mx.saluddigna.omnicanal.catalogo.domain.Clinica;
import mx.saluddigna.omnicanal.catalogo.domain.Estudio;
import mx.saluddigna.omnicanal.eventos.domain.Canal;
import mx.saluddigna.omnicanal.identidad.PacienteRepository;
import mx.saluddigna.omnicanal.identidad.domain.Paciente;
import mx.saluddigna.omnicanal.seguridad.PasswordService;
import mx.saluddigna.omnicanal.tramite.TramiteRepository;
import mx.saluddigna.omnicanal.tramite.TramiteService;
import mx.saluddigna.omnicanal.tramite.domain.Tramite;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Siembra demo, idempotente: si V2/V3 ya cargaron el catalogo y a los
 * pacientes (perfil Postgres), aqui solo faltan los tramites.
 * Si la base esta vacia (perfil H2), siembra todo.
 *
 * 5 usuarios ejemplo, cada uno en un estado distinto del flujo,
 * para que el asesor, WhatsApp y el Journey tengan casos que mostrar:
 * - Ana Garcia Lopez      2220000001  COTIZADO              (ana123)
 * - Luis Hernandez Torres 2221112233  CLINICA_SELECCIONADA  (luis123)
 * - Maria Fernanda Ruiz   2224445566  CONFIRMADO (cita 2026-11-24 10:00) (maria123)
 * - Carlos Mendoza Vega   2387778899  ATENDIDO              (carlos123)
 * - Fernanda Lopez Diaz   2229990011  DATOS_PENDIENTES      (fernanda123)
 * Contraseñas demo = primer nombre en minúsculas + 123, guardadas como
 * hash PBKDF2 (nunca en texto plano). Si el paciente ya tiene hash, no se pisa.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private final PacienteRepository pacientes;
    private final EstudioRepository estudios;
    private final ClinicaRepository clinicas;
    private final TramiteRepository tramites;
    private final TramiteService tramiteService;
    private final PasswordService passwords;

    public DataSeeder(PacienteRepository pacientes, EstudioRepository estudios,
                      ClinicaRepository clinicas, TramiteRepository tramites,
                      TramiteService tramiteService, PasswordService passwords) {
        this.pacientes = pacientes;
        this.estudios = estudios;
        this.clinicas = clinicas;
        this.tramites = tramites;
        this.tramiteService = tramiteService;
        this.passwords = passwords;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (estudios.count() == 0) {
            estudios.saveAll(List.of(
                    new Estudio("Ultrasonido abdominal", "Ultrasonido de abdomen completo", new BigDecimal("850.00"), 30),
                    new Estudio("Perfil tiroideo", "Perfil tiroideo en sangre (TSH, T3, T4)", new BigDecimal("620.00"), 15),
                    new Estudio("Radiografia torax", "Radiografia de torax PA y lateral", new BigDecimal("450.00"), 20),
                    new Estudio("Analisis sangre", "Biometria hematica completa", new BigDecimal("380.00"), 10)));
        }
        if (clinicas.count() == 0) {
            clinicas.saveAll(List.of(
                    new Clinica("Clinica Puebla Centro", "Puebla", "Av. Juarez 123", "2220000000"),
                    new Clinica("Clinica Salud Digna Tehuacan", "Tehuacan", "Calle 5 Sur 45", "2380000000")));
        }

        Estudio ultrasonido = estudioPorNombre("Ultrasonido");
        Estudio tiroideo = estudioPorNombre("Perfil tiroideo");
        Estudio radiografia = estudioPorNombre("Radiografia");
        Estudio analisis = estudioPorNombre("Analisis");
        Clinica puebla = clinicaPorNombre("Puebla");
        Clinica tehuacan = clinicaPorNombre("Tehuacan");

        // 1. Ana — COTIZADO
        Paciente ana = paciente("Ana Garcia Lopez", "2220000001", "ana.demo@saluddigna.test", LocalDate.of(1990, 5, 14), "ana123");
        tramiteCotizado(ana, ultrasonido);

        // 2. Luis — CLINICA_SELECCIONADA
        Paciente luis = paciente("Luis Hernandez Torres", "2221112233", "luis.demo@saluddigna.test", LocalDate.of(1985, 3, 22), "luis123");
        Tramite tLuis = tramiteCotizado(luis, tiroideo);
        if (tLuis != null && tLuis.getEstado().name().equals("COTIZADO")) {
            tramiteService.seleccionarClinica(tLuis.getId(), puebla.getId());
        }

        // 3. Maria — CONFIRMADO
        Paciente maria = paciente("Maria Fernanda Ruiz", "2224445566", "maria.demo@saluddigna.test", LocalDate.of(1992, 8, 9), "maria123");
        Tramite tMaria = tramiteCotizado(maria, radiografia);
        if (tMaria != null && tMaria.getEstado().name().equals("COTIZADO")) {
            tramiteService.seleccionarClinica(tMaria.getId(), tehuacan.getId());
            tramiteService.confirmar(tMaria.getId(), LocalDateTime.of(2026, 11, 24, 10, 0));
        }

        // 4. Carlos — ATENDIDO
        Paciente carlos = paciente("Carlos Mendoza Vega", "2387778899", "carlos.demo@saluddigna.test", LocalDate.of(1978, 11, 30), "carlos123");
        Tramite tCarlos = tramiteCotizado(carlos, analisis);
        if (tCarlos != null && tCarlos.getEstado().name().equals("COTIZADO")) {
            tramiteService.seleccionarClinica(tCarlos.getId(), puebla.getId());
            tramiteService.confirmar(tCarlos.getId(), LocalDateTime.of(2026, 11, 23, 9, 0));
            tramiteService.concluirAtencion(tCarlos.getId());
        }

        // 5. Fernanda — DATOS_PENDIENTES
        Paciente fernanda = paciente("Fernanda Lopez Diaz", "2229990011", "fernanda.demo@saluddigna.test", LocalDate.of(2001, 6, 17), "fernanda123");
        if (tramites.findByPacienteIdOrderByCreatedAtDesc(fernanda.getId()).isEmpty()) {
            Tramite t = tramiteService.crearTramite(fernanda.getId(), null, Canal.WEB.name());
            tramiteService.marcarDatosPendientes(t.getId(), Map.of("curp", "Pendiente: CURP", "ordenMedica", "Pendiente: orden medica"));
        }
    }

    /**
     * Busca al paciente por teléfono o lo crea. Si existe sin contraseña
     * (cuenta de antes de V4 o creada por la Web al cotizar), le asigna
     * la contraseña demo; si ya tiene hash, NO lo pisa (idempotente).
     */
    private Paciente paciente(String nombre, String telefono, String email, LocalDate fechaNacimiento, String password) {
        String tel = mx.saluddigna.omnicanal.identidad.TelefonoUtil.normalizar(telefono);
        Paciente p = pacientes.findByTelefono(tel)
                .orElseGet(() -> pacientes.save(new Paciente(nombre, tel, email, fechaNacimiento)));
        if (p.getPasswordHash() == null || p.getPasswordHash().isBlank()) {
            p.setPasswordHash(passwords.hash(password));
            p = pacientes.save(p);
        }
        return p;
    }

    private Estudio estudioPorNombre(String fragmento) {
        return estudios.findAll().stream()
                .filter(e -> e.getNombre().contains(fragmento))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Falta el estudio " + fragmento + " en el seed"));
    }

    private Clinica clinicaPorNombre(String fragmento) {
        return clinicas.findAll().stream()
                .filter(c -> c.getNombre().contains(fragmento))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Falta la clinica " + fragmento + " en el seed"));
    }

    /** Crea el tramite de un paciente y lo deja COTIZADO, o devuelve el existente. */
    private Tramite tramiteCotizado(Paciente paciente, Estudio estudio) {
        List<Tramite> existentes = tramites.findByPacienteIdOrderByCreatedAtDesc(paciente.getId());
        if (!existentes.isEmpty()) {
            return existentes.get(0);
        }
        Tramite demo = tramiteService.crearTramite(paciente.getId(), estudio.getId(), Canal.WEB.name());
        return tramiteService.cotizar(demo.getId(), estudio.getId());
    }
}
