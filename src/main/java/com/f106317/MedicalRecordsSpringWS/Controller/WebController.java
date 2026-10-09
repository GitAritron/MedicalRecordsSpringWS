package com.f106317.MedicalRecordsSpringWS.Controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

// Injects Keycloak config into the SPA shell so it's not hardcoded in JS.
@Controller
public class WebController {

    private final String keycloakUrl;
    private final String keycloakRealm;
    private final String keycloakClientId;

    public WebController(@Value("${app.keycloak.public-url}") String keycloakUrl,
                         @Value("${app.keycloak.realm}") String keycloakRealm,
                         @Value("${app.keycloak.client-id}") String keycloakClientId) {
        this.keycloakUrl = keycloakUrl;
        this.keycloakRealm = keycloakRealm;
        this.keycloakClientId = keycloakClientId;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("keycloakUrl", keycloakUrl);
        model.addAttribute("keycloakRealm", keycloakRealm);
        model.addAttribute("keycloakClientId", keycloakClientId);
        return "index";
    }
}
