package com.f106317.MedicalRecordsSpringWS.Controller;

import com.f106317.MedicalRecordsSpringWS.DTO.PatientRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.PatientResponse;
import com.f106317.MedicalRecordsSpringWS.Service.InsuranceService;
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
 * Web layer tests for {@link PatientController}.
 * Tests CRUD endpoints and insurance verification with mocked security.
 */
@WebMvcTest(PatientController.class)
class PatientControllerTest {

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
    private PatientService patientService;

    @MockitoBean
    private InsuranceService insuranceService;

    @MockitoBean
    private com.f106317.MedicalRecordsSpringWS.Service.KeycloakAdminService keycloakAdminService;

    private final PatientResponse samplePatient = new PatientResponse(1L, null, "1234567890", "John", "Doe", 1L, "Dr. House MD");

    /** Verifies GET /api/patients returns a list of patients for ADMIN users. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void getAllPatients() throws Exception {
        when(patientService.getAllPatients()).thenReturn(List.of(samplePatient));
        mockMvc.perform(get("/api/patients"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].firstName").value("John"));
    }

    /** Verifies GET /api/patients/{id} returns a single patient for PATIENT users. */
    @Test
    @WithMockUser(roles = "PATIENT")
    void getPatientById() throws Exception {
        when(patientService.getPatientById(eq(1L), any())).thenReturn(samplePatient);
        mockMvc.perform(get("/api/patients/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("John"));
    }

    /** Verifies POST /api/patients creates a patient and returns 201 Created. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void createPatient() throws Exception {
        when(patientService.createPatient(any(), any())).thenReturn(samplePatient);
        mockMvc.perform(post("/api/patients")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"keycloakId\":\"33333333-3333-3333-3333-333333333333\", \"egn\":\"1234567890\", \"firstName\":\"John\", \"lastName\":\"Doe\", \"gpDoctorId\":1}"))
                .andExpect(status().isCreated());
    }

    /** Verifies PUT /api/patients/{id} updates a patient and returns 200 OK. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void updatePatient() throws Exception {
        when(patientService.updatePatient(eq(1L), any(), any())).thenReturn(samplePatient);
        mockMvc.perform(put("/api/patients/1")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"keycloakId\":\"33333333-3333-3333-3333-333333333333\", \"egn\":\"1234567890\", \"firstName\":\"John\", \"lastName\":\"Doe\", \"gpDoctorId\":1}"))
                .andExpect(status().isOk());
    }

    /** Verifies DELETE /api/patients/{id} returns 204 No Content. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void deletePatient() throws Exception {
        mockMvc.perform(delete("/api/patients/1").with(csrf()))
                .andExpect(status().isNoContent());
    }

    /** Verifies GET /api/patients/{id}/insurance returns insurance status. */
    @Test
    @WithMockUser(roles = "PATIENT")
    void checkInsurance() throws Exception {
        when(insuranceService.hasContinuousInsurance(1L)).thenReturn(true);
        mockMvc.perform(get("/api/patients/1/insurance"))
                .andExpect(status().isOk())
                .andExpect(content().string("true"));
    }

    @Test
    @WithMockUser(roles = "PATIENT")
    void updatePatientGp() throws Exception {
        when(patientService.updatePatientGp(eq(1L), eq(2L), any())).thenReturn(samplePatient);
        mockMvc.perform(patch("/api/patients/1/gp?gpDoctorId=2").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("John"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void recordInsurancePayment() throws Exception {
        mockMvc.perform(post("/api/patients/1/insurance")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"patientId\":1, \"insuranceDate\":\"2026-08-01\", \"paidOn\":\"2026-08-05\"}"))
                .andExpect(status().isCreated());
    }
}
