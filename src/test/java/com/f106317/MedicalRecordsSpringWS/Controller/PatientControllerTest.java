package com.f106317.MedicalRecordsSpringWS.Controller;

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

// Permit-all security - @WithMockUser roles aren't enforced here, see EndpointAuthorizationMatrixTest.
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
    private com.f106317.MedicalRecordsSpringWS.Service.AccountProvisioningService accountProvisioning;

    // Not about Keycloak - default to "no account was asked for" (overwritten if the provisioning outcome is being tested), since a bare mock returns null.
    @org.junit.jupiter.api.BeforeEach
    void stubAccountProvisioning() {
        when(accountProvisioning.provisionIfRequested(any(), any(), any(), any(), any()))
                .thenReturn(com.f106317.MedicalRecordsSpringWS.Service
                        .AccountProvisioningService.ProvisionedAccount.none());
        // Deleting reads the record first, to learn which account to take the role back from.
        when(patientService.getPatientById(any(), any())).thenReturn(samplePatient);
    }

    private final PatientResponse samplePatient = new PatientResponse(1L, null, "1234567890", "John", "Doe", 1L, "Dr. House MD", null);

    @Test
    @WithMockUser(roles = "ADMIN")
    void getAllPatients() throws Exception {
        when(patientService.getAllPatients()).thenReturn(List.of(samplePatient));
        mockMvc.perform(get("/api/patients"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].firstName").value("John"));
    }

    @Test
    @WithMockUser(roles = "PATIENT")
    void getPatientById() throws Exception {
        when(patientService.getPatientById(eq(1L), any())).thenReturn(samplePatient);
        mockMvc.perform(get("/api/patients/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("John"));
    }

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

    @Test
    @WithMockUser(roles = "ADMIN")
    void deletePatient() throws Exception {
        mockMvc.perform(delete("/api/patients/1").with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "PATIENT")
    void checkInsurance() throws Exception {
        // Month count, not just a boolean - "not insured" alone can't tell one missed month from never paid.
        when(insuranceService.getStatus(1L)).thenReturn(
                new com.f106317.MedicalRecordsSpringWS.DTO.InsuranceStatusResponse(true, List.of()));
        mockMvc.perform(get("/api/patients/1/insurance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insured").value(true))
                .andExpect(jsonPath("$.unpaidMonths").isEmpty());
    }

    @Test
    @WithMockUser(roles = "PATIENT")
    void checkInsurance_reportsHowFarShortCoverageFalls() throws Exception {
        when(insuranceService.getStatus(1L)).thenReturn(
                new com.f106317.MedicalRecordsSpringWS.DTO.InsuranceStatusResponse(false,
                        List.of(new com.f106317.MedicalRecordsSpringWS.DTO.ReportDTO.UnpaidMonth(2026, 4))));
        mockMvc.perform(get("/api/patients/1/insurance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insured").value(false))
                // Naming the month is what turns "you are short" into something actionable.
                .andExpect(jsonPath("$.unpaidMonths[0].year").value(2026))
                .andExpect(jsonPath("$.unpaidMonths[0].month").value(4));
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
