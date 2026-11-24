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
 * - Ana Garcia Lopez      +52 222 000 0001  COTIZADO
 * - Luis Hernandez Torres +52 222 111 2233  CLINICA_SELECCIONADA
 * - Maria Fernanda Ruiz   +52 222 444 5566  CONFIRMADO (cita 2026-11-24 10:00)
 * - Carlos Mendoza Vega   +52 238 777 8899  ATENDIDO
 * - Fernanda Lopez Diaz   +52 222 999 0011  DATOS_PENDIENTES
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private final PacienteRepository pacientes;
    private final EstudioRepository estudios;
    private final ClinicaRepository clinicas;
    private final TramiteRepository tramites;
    private final TramiteService tramiteService;

    public DataSeeder(PacienteRepository pacientes, EstudioRepository estudios,
                      ClinicaRepository clinicas, TramiteRepository tramites,
                      TramiteService tramiteService) {
        this.pacientes = pacientes;
        this.estudios = estudios;
        this.clinicas = clinicas;
        this.tramites = tramites;
        this.tramiteService = tramiteService;
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
                    new Clinica("Clinica Puebla Centro", "Puebla", "Av. Juarez 123", "+52 222 000 0000"),
                    new Clinica("Clinica Salud Digna Tehuacan", "Tehuacan", "Calle 5 Sur 45", "+52 238 000 0000")));
        }

        Estudio ultrasonido = estudioPorNombre("Ultrasonido");
        Estudio tiroideo = estudioPorNombre("Perfil tiroideo");
        Estudio radiografia = estudioPorNombre("Radiografia");
        Estudio analisis = estudioPorNombre("Analisis");
        Clinica puebla = clinicaPorNombre("Puebla");
        Clinica tehuacan = clinicaPorNombre("Tehuacan");

        // 1. Ana — COTIZADO
        Paciente ana = paciente("Ana Garcia Lopez", "+52 222 000 0001", "ana.demo@saluddigna.test", LocalDate.of(1990, 5, 14));
        tramiteCotizado(ana, ultrasonido);

        // 2. Luis — CLINICA_SELECCIONADA
        Paciente luis = paciente("Luis Hernandez Torres", "+52 222 111 2233", "luis.demo@saluddigna.test", LocalDate.of(1985, 3, 22));
        Tramite tLuis = tramiteCotizado(luis, tiroideo);
        if (tLuis != null && tLuis.getEstado().name().equals("COTIZADO")) {
            tramiteService.seleccionarClinica(tLuis.getId(), puebla.getId());
        }

        // 3. Maria — CONFIRMADO
        Paciente maria = paciente("Maria Fernanda Ruiz", "+52 222 444 5566", "maria.demo@saluddigna.test", LocalDate.of(1992, 8, 9));
        Tramite tMaria = tramiteCotizado(maria, radiografia);
        if (tMaria != null && tMaria.getEstado().name().equals("COTIZADO")) {
            tramiteService.seleccionarClinica(tMaria.getId(), tehuacan.getId());
            tramiteService.confirmar(tMaria.getId(), LocalDateTime.of(2026, 11, 24, 10, 0));
        }

        // 4. Carlos — ATENDIDO
        Paciente carlos = paciente("Carlos Mendoza Vega", "+52 238 777 8899", "carlos.demo@saluddigna.test", LocalDate.of(1978, 11, 30));
        Tramite tCarlos = tramiteCotizado(carlos, analisis);
        if (tCarlos != null && tCarlos.getEstado().name().equals("COTIZADO")) {
            tramiteService.seleccionarClinica(tCarlos.getId(), puebla.getId());
            tramiteService.confirmar(tCarlos.getId(), LocalDateTime.of(2026, 11, 23, 9, 0));
            tramiteService.concluirAtencion(tCarlos.getId());
        }

        // 5. Fernanda — DATOS_PENDIENTES
        Paciente fernanda = paciente("Fernanda Lopez Diaz", "+52 222 999 0011", "fernanda.demo@saluddigna.test", LocalDate.of(2001, 6, 17));
        if (tramites.findByPacienteIdOrderByCreatedAtDesc(fernanda.getId()).isEmpty()) {
            Tramite t = tramiteService.crearTramite(fernanda.getId(), null, Canal.WEB.name());
            tramiteService.marcarDatosPendientes(t.getId(), Map.of("curp", "Pendiente: CURP", "ordenMedica", "Pendiente: orden medica"));
        }
    }

    private Paciente paciente(String nombre, String telefono, String email, LocalDate fechaNacimiento) {
        return pacientes.findByTelefono(telefono)
                .orElseGet(() -> pacientes.save(new Paciente(nombre, telefono, email, fechaNacimiento)));
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
