package mx.saluddigna.omnicanal.canales;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Salud del backend. El front lo usa para saber si hay backend vivo. */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "servicio", "omnicanal-salud-digna");
    }
}
