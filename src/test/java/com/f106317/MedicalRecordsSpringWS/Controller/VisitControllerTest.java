package com.f106317.MedicalRecordsSpringWS.Controller;

import com.f106317.MedicalRecordsSpringWS.DTO.*;
import com.f106317.MedicalRecordsSpringWS.Service.VisitService;
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

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Web layer tests for {@link VisitController}.
 * Verifies visit creation, sick-leave assignments, and patient visit lookups.
 */
@WebMvcTest(VisitController.class)
class VisitControllerTest {

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
    private VisitService visitService;

    private final VisitResponse sampleVisit = new VisitResponse(1L, 1L, "John Doe", 1L, "Dr. House", LocalDate.now(), LocalTime.now(), List.of(), List.of(), null);

    /** Verifies GET /api/visits returns a list of visits for ADMIN users. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void getAllVisits() throws Exception {
        when(visitService.getAllVisits()).thenReturn(List.of(sampleVisit));
        mockMvc.perform(get("/api/visits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].patientName").value("John Doe"));
    }

    /** Verifies GET /api/visits/{id} returns a single visit for PATIENT users. */
    @Test
    @WithMockUser(roles = "PATIENT")
    void getVisitById() throws Exception {
        when(visitService.getVisitById(org.mockito.ArgumentMatchers.eq(1L), any())).thenReturn(sampleVisit);
        mockMvc.perform(get("/api/visits/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patientName").value("John Doe"));
    }

    /** Verifies POST /api/visits creates a visit and returns 201 Created. */
    @Test
    @WithMockUser(roles = "DOCTOR")
    void createVisit() throws Exception {
        when(visitService.createVisit(any(), any())).thenReturn(sampleVisit);
        mockMvc.perform(post("/api/visits")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"patientId\":1, \"doctorId\":1, \"visitDate\":\"2026-07-15\", \"visitTime\":\"10:00:00\"}"))
                .andExpect(status().isCreated());
    }

    /** Verifies POST /api/visits/diagnosis attaches a diagnosis to a visit. */
    @Test
    @WithMockUser(roles = "DOCTOR")
    void createDiagnosis() throws Exception {
        when(visitService.createDiagnosis(any(), any())).thenReturn(new DiagnosisResponse(1L, 1L, "Flu"));
        mockMvc.perform(post("/api/visits/diagnosis")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"visitId\":1, \"name\":\"Flu\"}"))
                .andExpect(status().isCreated());
    }

    /** Verifies POST /api/visits/prescription attaches a prescription to a visit. */
    @Test
    @WithMockUser(roles = "DOCTOR")
    void createPrescription() throws Exception {
        when(visitService.createPrescription(any(), any())).thenReturn(new PrescriptionResponse(1L, 1L, "Aspirin"));
        mockMvc.perform(post("/api/visits/prescription")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"visitId\":1, \"note\":\"Aspirin\"}"))
                .andExpect(status().isCreated());
    }

    /** Verifies POST /api/visits/sickleave attaches a sick leave to a visit. */
    @Test
    @WithMockUser(roles = "DOCTOR")
    void createSickLeave() throws Exception {
        when(visitService.createSickLeave(any(), any())).thenReturn(new SickLeaveResponse(1L, 1L, LocalDate.now(), 5));
        mockMvc.perform(post("/api/visits/sickleave")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"visitId\":1, \"validFrom\":\"2026-07-15\", \"validForDays\":5}"))
                .andExpect(status().isCreated());
    }


    /** Verifies GET /api/visits/prescription/{id} returns a prescription. */
    @Test
    @WithMockUser(roles = "DOCTOR")
    void getPrescriptionById() throws Exception {
        when(visitService.getPrescriptionById(org.mockito.ArgumentMatchers.eq(1L), any()))
                .thenReturn(new PrescriptionResponse(1L, 1L, "Aspirin 100mg"));
        mockMvc.perform(get("/api/visits/prescription/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("Aspirin 100mg"));
    }

    /** Verifies PUT /api/visits/prescription/{id} updates a prescription. */
    @Test
    @WithMockUser(roles = "DOCTOR")
    void updatePrescription() throws Exception {
        when(visitService.updatePrescription(org.mockito.ArgumentMatchers.eq(1L), any(), any()))
                .thenReturn(new PrescriptionResponse(1L, 1L, "Updated Note"));
        mockMvc.perform(put("/api/visits/prescription/1")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"visitId\":1, \"note\":\"Updated Note\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("Updated Note"));
    }

    /** Verifies DELETE /api/visits/prescription/{id} deletes a prescription. */
    @Test
    @WithMockUser(roles = "DOCTOR")
    void deletePrescription() throws Exception {
        mockMvc.perform(delete("/api/visits/prescription/1").with(csrf()))
                .andExpect(status().isNoContent());
    }

    /** Verifies DELETE /api/visits/{id} deletes a visit. */
    @Test
    @WithMockUser(roles = "DOCTOR")
    void deleteVisit() throws Exception {
        mockMvc.perform(delete("/api/visits/1").with(csrf()))
                .andExpect(status().isNoContent());
    }
}
