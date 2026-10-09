package com.f106317.MedicalRecordsSpringWS.Controller;

import com.f106317.MedicalRecordsSpringWS.DTO.DoctorResponse;
import com.f106317.MedicalRecordsSpringWS.Service.DoctorService;
import com.f106317.MedicalRecordsSpringWS.Service.PatientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Web layer tests for {@link DoctorController}.
 * Uses MockMvc with a permissive security config to test endpoint routing and JSON serialization.
 */
@WebMvcTest(DoctorController.class)
class DoctorControllerTest {

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

    @MockitoBean
    private DoctorService doctorService;

    @MockitoBean
    private PatientService patientService;

    @MockitoBean
    private com.f106317.MedicalRecordsSpringWS.Service.AccountProvisioningService accountProvisioning;

    /** See PatientControllerTest: default the provisioning outcome to "no account was asked for". */
    @org.junit.jupiter.api.BeforeEach
    void stubAccountProvisioning() {
        when(accountProvisioning.provisionIfRequested(any(), any(), any(), any(), any()))
                .thenReturn(com.f106317.MedicalRecordsSpringWS.Service
                        .AccountProvisioningService.ProvisionedAccount.none());
        // Deleting reads the record first, to learn which account to take the role back from.
        when(doctorService.getDoctorById(any())).thenReturn(sampleDoctor);
    }

    private final DoctorResponse sampleDoctor = new DoctorResponse(1L, "kc-id-123", "John", "Doe", true, List.of(), null, false);

    /** Verifies GET /api/doctors returns a list of doctors for ADMIN users. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void getAllDoctors() throws Exception {
        when(doctorService.getAllDoctors()).thenReturn(List.of(sampleDoctor));
        mockMvc.perform(get("/api/doctors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].firstName").value("John"));
    }

    /** Verifies GET /api/doctors/{id} returns a single doctor for PATIENT users. */
    @Test
    @WithMockUser(roles = "PATIENT")
    void getDoctorById() throws Exception {
        when(doctorService.getDoctorById(1L)).thenReturn(sampleDoctor);
        mockMvc.perform(get("/api/doctors/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("John"));
    }

    /** Verifies POST /api/doctors creates a doctor and returns 201 Created. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void createDoctor() throws Exception {
        when(doctorService.createDoctor(any(), any())).thenReturn(sampleDoctor);
        mockMvc.perform(post("/api/doctors")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"keycloakId\":\"22222222-2222-2222-2222-222222222222\", \"firstName\":\"John\", \"lastName\":\"Doe\", \"isGP\":true, \"specializationIds\":[]}"))
                .andExpect(status().isCreated());
    }

    /** Verifies PUT /api/doctors/{id} updates a doctor and returns 200 OK. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void updateDoctor() throws Exception {
        when(doctorService.updateDoctor(eq(1L), any(), any())).thenReturn(sampleDoctor);
        mockMvc.perform(put("/api/doctors/1")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"keycloakId\":\"22222222-2222-2222-2222-222222222222\", \"firstName\":\"John\", \"lastName\":\"Doe\", \"isGP\":true, \"specializationIds\":[]}"))
                .andExpect(status().isOk());
    }

    /** Verifies DELETE /api/doctors/{id} returns 204 No Content. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteDoctor() throws Exception {
        mockMvc.perform(delete("/api/doctors/1").with(csrf()))
                .andExpect(status().isNoContent());
    }
}
