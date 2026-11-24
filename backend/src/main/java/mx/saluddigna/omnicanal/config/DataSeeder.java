package mx.saluddigna.omnicanal.config;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import mx.saluddigna.omnicanal.seguridad.UsuarioStaffRepository;
import mx.saluddigna.omnicanal.seguridad.domain.UsuarioStaff;
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
 * - Ana Garcia Lopez      2220000001  GALA900514MDFRPN00  COTIZADO              (ana123)
 * - Luis Hernandez Torres 2221112233  HETL850322HDFRRS01  CLINICA_SELECCIONADA  (luis123)
 * - Maria Fernanda Ruiz   2224445566  RUXF920809MDFZXR02  CONFIRMADO (cita al dia siguiente, 10:00) (maria123)
 * - Carlos Mendoza Vega   2387778899  MEVC781130HDFNGR03  ATENDIDO              (carlos123)
 * - Fernanda Lopez Diaz   2229990011  LODF010617MDFPZR04  DATOS_PENDIENTES      (fernanda123)
 * Contraseñas demo = primer nombre en minúsculas + 123, guardadas como
 * hash PBKDF2 (nunca en texto plano). Si el paciente ya tiene hash, no se pisa.
 * Las CURPs son DE DEMOSTRACIÓN (formato válido, derivadas del nombre,
 * la fecha de nacimiento, el sexo y la entidad DF; no son CURPs reales
 * de ninguna persona). Si el paciente ya tiene CURP, no se pisa.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private final PacienteRepository pacientes;
    private final EstudioRepository estudios;
    private final ClinicaRepository clinicas;
    private final TramiteRepository tramites;
    private final TramiteService tramiteService;
    private final PasswordService passwords;
    private final UsuarioStaffRepository staff;

    public DataSeeder(PacienteRepository pacientes, EstudioRepository estudios,
                      ClinicaRepository clinicas, TramiteRepository tramites,
                      TramiteService tramiteService, PasswordService passwords,
                      UsuarioStaffRepository staff) {
        this.pacientes = pacientes;
        this.estudios = estudios;
        this.clinicas = clinicas;
        this.tramites = tramites;
        this.tramiteService = tramiteService;
        this.passwords = passwords;
        this.staff = staff;
    }

    @Override
    @Transactional
    public void run(String... args) {
        // Personal en Postgres (tabla usuarios_staff, V6): si no existe,
        // se crea con hash PBKDF2; si ya existe, no se pisa (idempotente).
        usuarioStaff("Administrador", "admin", "admin123", "ADMINISTRADOR");
        usuarioStaff("Asesor Call Center", "asesor", "asesor123", "ASESOR");
        usuarioStaff("Alejandro Ruiz", "doctor", "doctor123", "DOCTOR");

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
        Paciente ana = paciente("Ana Garcia Lopez", "2220000001", "ana.demo@saluddigna.test", LocalDate.of(1990, 5, 14), "ana123", "GALA900514MDFRPN00");
        tramiteCotizado(ana, ultrasonido);

        // 2. Luis — CLINICA_SELECCIONADA
        Paciente luis = paciente("Luis Hernandez Torres", "2221112233", "luis.demo@saluddigna.test", LocalDate.of(1985, 3, 22), "luis123", "HETL850322HDFRRS01");
        Tramite tLuis = tramiteCotizado(luis, tiroideo);
        if (tLuis != null && tLuis.getEstado().name().equals("COTIZADO")) {
            tramiteService.seleccionarClinica(tLuis.getId(), puebla.getId());
        }

        // 3. Maria — CONFIRMADO
        // La cita semilla es RELATIVA (manana 10:00): con fecha fija el
        // seeder tumbaba el arranque en cuanto la fecha quedaba en el
        // pasado, porque confirmar() rechaza citas pasadas (regla real).
        Paciente maria = paciente("Maria Fernanda Ruiz", "2224445566", "maria.demo@saluddigna.test", LocalDate.of(1992, 8, 9), "maria123", "RUXF920809MDFZXR02");
        Tramite tMaria = tramiteCotizado(maria, radiografia);
        if (tMaria != null && tMaria.getEstado().name().equals("COTIZADO")) {
            tramiteService.seleccionarClinica(tMaria.getId(), tehuacan.getId());
            tramiteService.confirmar(tMaria.getId(), LocalDate.now().plusDays(1).atTime(10, 0));
        }

        // 4. Carlos — ATENDIDO (cita semilla relativa: manana 09:00,
        // misma razon que Maria: la fecha fija 23/11/2026 09:00 ya quedo
        // en el pasado y confirmar() la rechazaba con 400, tumbando el arranque).
        Paciente carlos = paciente("Carlos Mendoza Vega", "2387778899", "carlos.demo@saluddigna.test", LocalDate.of(1978, 11, 30), "carlos123", "MEVC781130HDFNGR03");
        Tramite tCarlos = tramiteCotizado(carlos, analisis);
        if (tCarlos != null && tCarlos.getEstado().name().equals("COTIZADO")) {
            tramiteService.seleccionarClinica(tCarlos.getId(), puebla.getId());
            tramiteService.confirmar(tCarlos.getId(), LocalDate.now().plusDays(1).atTime(9, 0));
            tramiteService.concluirAtencion(tCarlos.getId());
        }

        // 5. Fernanda — DATOS_PENDIENTES
        Paciente fernanda = paciente("Fernanda Lopez Diaz", "2229990011", "fernanda.demo@saluddigna.test", LocalDate.of(2001, 6, 17), "fernanda123", "LODF010617MDFPZR04");
        if (tramites.findByPacienteIdOrderByCreatedAtDesc(fernanda.getId()).isEmpty()) {
            Tramite t = tramiteService.crearTramite(fernanda.getId(), null, Canal.WEB.name());
            tramiteService.marcarDatosPendientes(t.getId(), Map.of("curp", "Pendiente: CURP", "ordenMedica", "Pendiente: orden medica"));
        }
    }

    /**
     * Cuenta de personal: la crea si no existe (con hash PBKDF2); si ya
     * existe sin hash, se lo asigna; si ya tiene hash, NO lo pisa.
     */
    private UsuarioStaff usuarioStaff(String nombre, String usuario, String password, String rol) {
        UsuarioStaff u = staff.findByUsuario(usuario)
                .orElseGet(() -> staff.save(new UsuarioStaff(nombre, usuario, "", rol)));
        if (u.getPasswordHash() == null || u.getPasswordHash().isBlank()) {
            u.setPasswordHash(passwords.hash(password));
            u = staff.save(u);
        }
        return u;
    }

    /**
     * Busca al paciente por teléfono o lo crea. Si existe sin contraseña
     * (cuenta de antes de V4 o creada por la Web al cotizar), le asigna
     * la contraseña demo; si ya tiene hash, NO lo pisa (idempotente).
     * Si existe sin CURP, le asigna la CURP semilla; si ya tiene CURP,
     * NO se pisa (idempotente).
     */
    private Paciente paciente(String nombre, String telefono, String email, LocalDate fechaNacimiento, String password, String curp) {
        String tel = mx.saluddigna.omnicanal.identidad.TelefonoUtil.normalizar(telefono);
        Paciente p = pacientes.findByTelefono(tel)
                .orElseGet(() -> pacientes.save(new Paciente(nombre, tel, email, fechaNacimiento)));
        if (p.getCurp() == null || p.getCurp().isBlank()) {
            p.setCurp(curp);
            p = pacientes.save(p);
        }
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
