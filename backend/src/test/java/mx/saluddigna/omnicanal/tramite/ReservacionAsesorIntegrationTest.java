package mx.saluddigna.omnicanal.tramite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import mx.saluddigna.omnicanal.catalogo.ClinicaRepository;
import mx.saluddigna.omnicanal.catalogo.EstudioRepository;
import mx.saluddigna.omnicanal.catalogo.domain.Clinica;
import mx.saluddigna.omnicanal.catalogo.domain.Estudio;
import mx.saluddigna.omnicanal.config.DataSeeder;
import mx.saluddigna.omnicanal.identidad.PacienteRepository;
import mx.saluddigna.omnicanal.identidad.domain.Paciente;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class ReservacionAsesorIntegrationTest {
    @MockBean DataSeeder seeder;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PacienteRepository pacientes;
    @Autowired EstudioRepository estudios;
    @Autowired ClinicaRepository clinicas;

    @Test
    void creaCotizaYSeleccionaClinicaParaPacienteExistente() throws Exception {
        var paciente = new Paciente("Paciente de prueba", null, null, java.time.LocalDate.of(1990, 5, 14));
        paciente.setCurp("GALA900514MDFRPN00");
        paciente = pacientes.saveAndFlush(paciente);
        var estudio = estudios.saveAndFlush(new Estudio("Estudio de prueba", "Prueba", new java.math.BigDecimal("450"), 30));
        var clinica = clinicas.saveAndFlush(new Clinica("Clínica de prueba", "Puebla", null, null));
        String body = json.writeValueAsString(java.util.Map.of(
                "pacienteId", paciente.getId(), "estudioId", estudio.getId(), "canalOrigen", "CALL_CENTER"));

        String response = mvc.perform(post("/api/tramites").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paciente.id").value(paciente.getId().toString()))
                .andReturn().getResponse().getContentAsString();
        JsonNode tramite = json.readTree(response);
        String id = tramite.get("id").asText();

        mvc.perform(post("/api/tramites/{id}/cotizar", id).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("estudioId", estudio.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("COTIZADO"))
                .andExpect(jsonPath("$.paciente.curp").value(paciente.getCurp()))
                .andExpect(jsonPath("$.paciente.hibernateLazyInitializer").doesNotExist())
                .andExpect(jsonPath("$.paciente.passwordHash").doesNotExist());

        mvc.perform(post("/api/tramites/{id}/seleccionar-clinica", id).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("clinicaId", clinica.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CLINICA_SELECCIONADA"));
    }
}
