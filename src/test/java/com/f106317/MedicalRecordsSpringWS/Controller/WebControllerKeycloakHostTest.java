package com.f106317.MedicalRecordsSpringWS.Controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The SPA shell with Keycloak moved to another host.
 * <p>
 * Keycloak stamps a token's iss claim from the host the browser used to reach it, and the resource
 * server rejects any token whose iss differs from issuer-uri. So moving the browser-facing URL has
 * to move the issuer with it: the two tests below are one rule, seen from each side.
 * <p>
 * WHY a separate class rather than a @Nested one in WebControllerTest: a different property source
 * needs its own application context, and as a @Nested class that context came up without the web
 * security infrastructure (no HttpSecurity bean), so it could not build the test filter chain.
 * A top-level @WebMvcTest, set up like every other controller test here, does not have the problem.
 */
@WebMvcTest(WebController.class)
@TestPropertySource(properties = "app.keycloak.public-url=http://kc.example:9000")
class WebControllerKeycloakHostTest {

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
    void thePageFollowsTheProperty() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("src=\"http://kc.example:9000/js/keycloak.js\"")))
                .andExpect(content().string(containsString("data-keycloak-url=\"http://kc.example:9000\"")));
    }

    @Test
    void theIssuerFollowsItToo() {
        assertEquals("http://kc.example:9000/realms/medical-records",
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"));
    }
}
