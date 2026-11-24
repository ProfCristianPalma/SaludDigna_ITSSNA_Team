package mx.saluddigna.omnicanal.catalogo;

import java.util.List;
import mx.saluddigna.omnicanal.catalogo.domain.Clinica;
import mx.saluddigna.omnicanal.catalogo.domain.Estudio;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class CatalogoController {

    private final EstudioRepository estudios;
    private final ClinicaRepository clinicas;

    public CatalogoController(EstudioRepository estudios, ClinicaRepository clinicas) {
        this.estudios = estudios;
        this.clinicas = clinicas;
    }

    @GetMapping("/estudios")
    public List<Estudio> listarEstudios() {
        return estudios.findAll();
    }

    @GetMapping("/clinicas")
    public List<Clinica> listarClinicas() {
        return clinicas.findAll();
    }

    @GetMapping("/clinicas/{id}")
    public Clinica buscarClinica(@PathVariable Long id) {
        return clinicas.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Clinica no encontrada: " + id));
    }
}
