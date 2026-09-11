package com.f106317.MedicalRecordsSpringWS.Controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * The SPA shell is the one page every user loads before anything else, and it is where the
 * Keycloak adapter comes from - if these values fail to render, nobody can sign in at all.
 * These tests render the real Thymeleaf template, so they catch a broken expression, a renamed
 * model attribute or a missing property, not just the controller's return value.
 * WebControllerKeycloakHostTest covers the same page with Keycloak moved to another host.
 */
@WebMvcTest(WebController.class)
class WebControllerTest {

    @TestConfiguration
    static class TestSecurityConfig {
        @Bean
        public SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
            http.csrf(c -> c.disable())
                .authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment environment;

    @Test
    void rendersTheKeycloakSettingsFromConfiguration() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(content().string(containsString("src=\"http://localhost:8081/js/keycloak.js\"")))
                .andExpect(content().string(containsString("data-keycloak-url=\"http://localhost:8081\"")))
                .andExpect(content().string(containsString("data-keycloak-realm=\"medical-records\"")))
                .andExpect(content().string(containsString("data-keycloak-client-id=\"frontend-client\"")));
    }

    /** Refactoring the issuer into a derived value must not change what it resolves to by default. */
    @Test
    void defaultIssuerIsExactlyWhatItWasBefore() {
        assertEquals("http://localhost:8081/realms/medical-records",
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"));
    }
}
