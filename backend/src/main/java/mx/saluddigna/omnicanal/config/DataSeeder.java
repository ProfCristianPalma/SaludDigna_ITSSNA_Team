package mx.saluddigna.omnicanal.config;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
 * Siembra demo, idempotente por tabla: si V2__seed.sql ya cargo el
 * catalogo y a Ana (perfil Postgres), aqui solo falta el tramite demo.
 * Si la base esta vacia (perfil H2), siembra todo.
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

        Paciente ana = pacientes.findByTelefono("+52 222 000 0001")
                .orElseGet(() -> pacientes.save(new Paciente(
                        "Ana Garcia Lopez", "+52 222 000 0001", "ana.demo@saluddigna.test",
                        LocalDate.of(1990, 5, 14))));

        // Tramite demo de Ana en COTIZADO, creado por el servicio para que
        // el log de eventos quede completo (tramite.iniciado + cotizacion.creada).
        if (tramites.findByPacienteIdOrderByCreatedAtDesc(ana.getId()).isEmpty()) {
            Estudio ultrasonido = estudios.findAll().stream()
                    .filter(e -> e.getNombre().contains("Ultrasonido"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Falta el estudio Ultrasonido abdominal en el seed"));
            Tramite demo = tramiteService.crearTramite(ana.getId(), ultrasonido.getId(), Canal.WEB.name());
            tramiteService.cotizar(demo.getId(), ultrasonido.getId());
        }
    }
}
