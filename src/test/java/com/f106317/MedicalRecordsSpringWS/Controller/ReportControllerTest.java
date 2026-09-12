package com.f106317.MedicalRecordsSpringWS.Controller;

import com.f106317.MedicalRecordsSpringWS.DTO.ReportDTO;
import com.f106317.MedicalRecordsSpringWS.Service.DoctorService;
import com.f106317.MedicalRecordsSpringWS.Service.VisitService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web layer tests for {@link ReportController}.
 * Verifies that report endpoints return aggregated data correctly.
 */
@WebMvcTest(ReportController.class)
class ReportControllerTest {

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

    @MockitoBean
    private DoctorService doctorService;

    @MockitoBean
    private com.f106317.MedicalRecordsSpringWS.Service.AuditLogService auditLogService;

    /** Backs the health-insurance report — the "справка" §1 asks for, across the whole roster. */
    @MockitoBean
    private com.f106317.MedicalRecordsSpringWS.Service.InsuranceService insuranceService;

    /** Verifies GET /api/reports/diagnoses/common returns ranked diagnoses. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void getMostCommonDiagnoses() throws Exception {
        when(visitService.getMostCommonDiagnoses()).thenReturn(List.of(new ReportDTO.DiagnosisCount("Flu", 10L)));
        mockMvc.perform(get("/api/reports/diagnoses/common"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].diagnosisName").value("Flu"));
    }

    /** Verifies GET /api/reports/sickleaves/doctors returns doctors ranked by sick leaves issued. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void getDoctorsWithMostSickLeaves() throws Exception {
        when(visitService.getDoctorsWithMostSickLeaves()).thenReturn(List.of(new ReportDTO.DoctorSickLeaveCount(1L, "John Doe", 5L)));
        mockMvc.perform(get("/api/reports/sickleaves/doctors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].doctorName").value("John Doe"));
    }

    /** Verifies GET /api/reports/sickleaves/months returns months ranked by sick leave count. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void getMonthsWithMostSickLeaves() throws Exception {
        when(visitService.getMonthsWithMostSickLeaves()).thenReturn(List.of(new ReportDTO.SickLeaveMonthCount(2026, 1, 10L)));
        mockMvc.perform(get("/api/reports/sickleaves/months"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].month").value(1));
    }

    /** Verifies GET /api/reports/doctors/visits returns doctors ranked by visit count. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void getDoctorsWithVisitCount() throws Exception {
        when(doctorService.getDoctorsWithVisitCount()).thenReturn(List.of(new ReportDTO.DoctorVisitCount(1L, "John Doe", 20L)));
        mockMvc.perform(get("/api/reports/doctors/visits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].doctorName").value("John Doe"));
    }

    /** Verifies GET /api/reports/visits/period filters visits by date range. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void getVisitsInPeriod() throws Exception {
        when(visitService.getVisitsInPeriod(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new com.f106317.MedicalRecordsSpringWS.DTO.VisitResponse(1L, 1L, "Patient", 1L, "Doctor", java.time.LocalDate.now(), java.time.LocalTime.now(), List.of(), List.of(), null)));
        mockMvc.perform(get("/api/reports/visits/period?start=2026-01-01&end=2026-12-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].patientName").value("Patient"));
    }

    // ──────────────────────────────────────────────
    // Completeness
    // ──────────────────────────────────────────────

    /**
     * Reports answer the specification's questions, so a truncated answer is a wrong one.
     * The endpoints used to serve 50 rows by default and slice the rest away in memory — the full
     * result was already materialised, so the slice saved nothing and only hid rows. This pins the
     * replacement: every row the service returns reaches the caller, and a leftover {@code size}
     * in a bookmarked URL cannot reintroduce the cut.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void reportsReturnEveryRow() throws Exception {
        List<ReportDTO.DiagnosisCount> many = java.util.stream.IntStream.range(0, 250)
                .mapToObj(i -> new ReportDTO.DiagnosisCount("Diagnosis " + i, (long) i))
                .toList();
        when(visitService.getMostCommonDiagnoses()).thenReturn(many);

        mockMvc.perform(get("/api/reports/diagnoses/common"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(250));

        mockMvc.perform(get("/api/reports/diagnoses/common?page=3&size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(250));
    }
}
