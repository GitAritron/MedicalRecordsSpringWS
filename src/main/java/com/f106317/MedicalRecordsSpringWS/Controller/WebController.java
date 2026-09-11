package com.f106317.MedicalRecordsSpringWS.Controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the single-page application's shell.
 * <p>
 * WHY it passes Keycloak settings into the template: the request for this page carries no bearer
 * token - the SPA only authenticates after it has loaded - so nothing user-specific can be rendered
 * here. What the server can usefully supply is deployment configuration, which would otherwise be
 * hardcoded in both index.html and app.js. The URL comes from app.keycloak.public-url, the same
 * property issuer-uri is built from, so the page and the token check cannot disagree about where
 * Keycloak lives.
 */
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
