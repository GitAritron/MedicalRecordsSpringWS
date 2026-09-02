package com.f106317.MedicalRecordsSpringWS.Controller;

import com.f106317.MedicalRecordsSpringWS.Config.SecurityConfig;
import com.f106317.MedicalRecordsSpringWS.DTO.DoctorResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.PatientResponse;
import com.f106317.MedicalRecordsSpringWS.Service.DoctorService;
import com.f106317.MedicalRecordsSpringWS.Service.InsuranceService;
import com.f106317.MedicalRecordsSpringWS.Service.KeycloakAdminService;
import com.f106317.MedicalRecordsSpringWS.Service.PatientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security tests for the registration endpoints, running against the <em>real</em>
 * {@link SecurityConfig} so that {@code @PreAuthorize} is actually evaluated.
 * <p>
 * WHY: the other controller tests install a permit-all filter chain, which means their
 * {@code @WithMockUser} roles have no effect. These tests exist to pin down the one rule that
 * matters here — only an administrator may create doctors and patients directly, while any
 * signed-in user may register themselves through the dedicated self-registration endpoints.
 */
@WebMvcTest({DoctorController.class, PatientController.class})
@Import(SecurityConfig.class)
class RegistrationEndpointSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private DoctorService doctorService;
    @MockitoBean private PatientService patientService;
    @MockitoBean private InsuranceService insuranceService;
    @MockitoBean private KeycloakAdminService keycloakAdminService;

    /** Required by the OAuth2 resource server configured in SecurityConfig. */
    @MockitoBean private JwtDecoder jwtDecoder;

    private static final String DOCTOR_BODY = """
            {"firstName":"Ivan","lastName":"Petrov","isGP":true,"specializationIds":[]}""";
    private static final String DOCTOR_SELF_BODY = """
            {"firstName":"Ivan","lastName":"Petrov","specializationIds":[]}""";
    private static final String PATIENT_BODY = """
            {"egn":"1234567890","firstName":"John","lastName":"Doe"}""";

    // ── POST /api/doctors — administrators only ────────────────────────────

    /**
     * Regression test for the tautological rule {@code hasRole('ADMIN') or isAuthenticated()},
     * which collapsed to {@code isAuthenticated()} and let any signed-in user create doctors.
     */
    @Test
    @WithMockUser(roles = "PATIENT")
    void patientCannotCreateDoctor() throws Exception {
        mockMvc.perform(post("/api/doctors").contentType(MediaType.APPLICATION_JSON).content(DOCTOR_BODY))
                .andExpect(status().isForbidden());
        verify(doctorService, never()).createDoctor(any(), any());
    }

    @Test
    @WithMockUser(roles = "DOCTOR")
    void doctorCannotCreateDoctor() throws Exception {
        mockMvc.perform(post("/api/doctors").contentType(MediaType.APPLICATION_JSON).content(DOCTOR_BODY))
                .andExpect(status().isForbidden());
        verify(doctorService, never()).createDoctor(any(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanCreateDoctor() throws Exception {
        when(doctorService.createDoctor(any(), any()))
                .thenReturn(new DoctorResponse(1L, "kc-1", "Ivan", "Petrov", true, List.of()));
        mockMvc.perform(post("/api/doctors").contentType(MediaType.APPLICATION_JSON).content(DOCTOR_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    void anonymousCannotCreateDoctor() throws Exception {
        mockMvc.perform(post("/api/doctors").contentType(MediaType.APPLICATION_JSON).content(DOCTOR_BODY))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /api/patients — administrators only ───────────────────────────

    @Test
    @WithMockUser(roles = "PATIENT")
    void patientCannotCreatePatientDirectly() throws Exception {
        mockMvc.perform(post("/api/patients").contentType(MediaType.APPLICATION_JSON).content(PATIENT_BODY))
                .andExpect(status().isForbidden());
        verify(patientService, never()).createPatient(any(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanCreatePatient() throws Exception {
        when(patientService.createPatient(any(), any()))
                .thenReturn(new PatientResponse(1L, null, "1234567890", "John", "Doe", null, null));
        mockMvc.perform(post("/api/patients").contentType(MediaType.APPLICATION_JSON).content(PATIENT_BODY))
                .andExpect(status().isCreated());
    }

    // ── Self-registration — open to any signed-in user ─────────────────────

    @Test
    @WithMockUser
    void anySignedInUserMaySelfRegisterAsDoctor() throws Exception {
        when(doctorService.selfRegister(any(), any()))
                .thenReturn(new DoctorResponse(1L, "kc-1", "Ivan", "Petrov", false, List.of()));
        mockMvc.perform(post("/api/doctors/self-register")
                        .contentType(MediaType.APPLICATION_JSON).content(DOCTOR_SELF_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser
    void anySignedInUserMaySelfRegisterAsPatient() throws Exception {
        when(patientService.selfRegister(any(), any()))
                .thenReturn(new PatientResponse(1L, "kc-1", "1234567890", "John", "Doe", null, null));
        mockMvc.perform(post("/api/patients/self-register")
                        .contentType(MediaType.APPLICATION_JSON).content(PATIENT_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    void anonymousCannotSelfRegister() throws Exception {
        mockMvc.perform(post("/api/patients/self-register")
                        .contentType(MediaType.APPLICATION_JSON).content(PATIENT_BODY))
                .andExpect(status().isUnauthorized());
    }
}
