package com.f106317.MedicalRecordsSpringWS.Controller;

import com.f106317.MedicalRecordsSpringWS.Config.SecurityConfig;
import com.f106317.MedicalRecordsSpringWS.DTO.DoctorResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.PatientResponse;
import com.f106317.MedicalRecordsSpringWS.Service.DoctorService;
import com.f106317.MedicalRecordsSpringWS.Service.InsuranceService;
import com.f106317.MedicalRecordsSpringWS.Service.AccountProvisioningService;
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

// Real SecurityConfig: only admins create doctors/patients directly, anyone signed in can self-register.
@WebMvcTest({DoctorController.class, PatientController.class})
@Import(SecurityConfig.class)
class RegistrationEndpointSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private DoctorService doctorService;
    @MockitoBean private PatientService patientService;
    @MockitoBean private InsuranceService insuranceService;
    @MockitoBean private AccountProvisioningService accountProvisioning;

    // No account is provisioned - these tests are only about who can reach the endpoint.
    @org.junit.jupiter.api.BeforeEach
    void stubAccountProvisioning() {
        when(accountProvisioning.provisionIfRequested(any(), any(), any(), any(), any()))
                .thenReturn(AccountProvisioningService.ProvisionedAccount.none());
    }

    // Required by the OAuth2 resource server in SecurityConfig.
    @MockitoBean private JwtDecoder jwtDecoder;

    private static final String DOCTOR_BODY = """
            {"firstName":"Ivan","lastName":"Petrov","isGP":true,"specializationIds":[]}""";
    private static final String DOCTOR_SELF_BODY = """
            {"firstName":"Ivan","lastName":"Petrov"}""";
    private static final String PATIENT_BODY = """
            {"egn":"1234567890","firstName":"John","lastName":"Doe"}""";

    // ──────────────────────────────────────────────
    // POST /api/doctors: admin only
    // ──────────────────────────────────────────────

    // Regression: hasRole('ADMIN') or isAuthenticated() collapsed to isAuthenticated().
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
                .thenReturn(new DoctorResponse(1L, "kc-1", "Ivan", "Petrov", true, List.of(), null, false));
        mockMvc.perform(post("/api/doctors").contentType(MediaType.APPLICATION_JSON).content(DOCTOR_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    void anonymousCannotCreateDoctor() throws Exception {
        mockMvc.perform(post("/api/doctors").contentType(MediaType.APPLICATION_JSON).content(DOCTOR_BODY))
                .andExpect(status().isUnauthorized());
    }

    // ──────────────────────────────────────────────
    // POST /api/patients: admin only
    // ──────────────────────────────────────────────

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
                .thenReturn(new PatientResponse(1L, null, "1234567890", "John", "Doe", null, null, null));
        mockMvc.perform(post("/api/patients").contentType(MediaType.APPLICATION_JSON).content(PATIENT_BODY))
                .andExpect(status().isCreated());
    }

    // ──────────────────────────────────────────────
    // Self-registration: any signed-in user
    // ──────────────────────────────────────────────

    @Test
    @WithMockUser
    void anySignedInUserMaySelfRegisterAsDoctor() throws Exception {
        when(doctorService.selfRegister(any(), any()))
                .thenReturn(new DoctorResponse(1L, "kc-1", "Ivan", "Petrov", false, List.of(), null, true));
        mockMvc.perform(post("/api/doctors/self-register")
                        .contentType(MediaType.APPLICATION_JSON).content(DOCTOR_SELF_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser
    void anySignedInUserMaySelfRegisterAsPatient() throws Exception {
        when(patientService.selfRegister(any(), any())).thenReturn(
                new com.f106317.MedicalRecordsSpringWS.Service.PatientService.SelfRegistration(
                        new PatientResponse(1L, "kc-1", "1234567890", "John", "Doe", null, null, null), true));
        mockMvc.perform(post("/api/patients/self-register")
                        .contentType(MediaType.APPLICATION_JSON).content(PATIENT_BODY))
                .andExpect(status().isCreated());
    }

    // Repeating is the way out of a failed PATIENT grant, so it must answer. 200, not 201 - nothing was created.
    @Test
    @WithMockUser
    void repeatingPatientSelfRegistrationReturnsTheExistingRecord() throws Exception {
        when(patientService.selfRegister(any(), any())).thenReturn(
                new com.f106317.MedicalRecordsSpringWS.Service.PatientService.SelfRegistration(
                        new PatientResponse(1L, "kc-1", "1234567890", "John", "Doe", null, null, null), false));
        mockMvc.perform(post("/api/patients/self-register")
                        .contentType(MediaType.APPLICATION_JSON).content(PATIENT_BODY))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousCannotSelfRegister() throws Exception {
        mockMvc.perform(post("/api/patients/self-register")
                        .contentType(MediaType.APPLICATION_JSON).content(PATIENT_BODY))
                .andExpect(status().isUnauthorized());
    }
}
