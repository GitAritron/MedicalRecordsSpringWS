package com.f106317.MedicalRecordsSpringWS.Controller;

import com.f106317.MedicalRecordsSpringWS.Config.SecurityConfig;
import com.f106317.MedicalRecordsSpringWS.Service.AuditLogService;
import com.f106317.MedicalRecordsSpringWS.Service.AuthorizationService;
import com.f106317.MedicalRecordsSpringWS.Service.DoctorService;
import com.f106317.MedicalRecordsSpringWS.Service.InsuranceService;
import com.f106317.MedicalRecordsSpringWS.Service.AccountProvisioningService;
import com.f106317.MedicalRecordsSpringWS.Service.PatientService;
import com.f106317.MedicalRecordsSpringWS.Service.VisitService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The role matrix of specification §2, asserted endpoint by endpoint against the real
 * {@link SecurityConfig} so that {@code @PreAuthorize} is genuinely evaluated.
 * <p>
 * WHY this exists: the per-controller tests install a permit-all filter chain, so their
 * {@code @WithMockUser} roles have no effect and every access rule in the application went
 * unverified. This test only asserts <em>denials</em> — a rejected request never reaches the
 * handler, so no service stubbing is needed and each case stays honest.
 * <p>
 * Note on bodies: {@code @PreAuthorize} is enforced when the controller method is invoked, which
 * happens <em>after</em> Spring resolves and validates {@code @RequestBody}. Write endpoints
 * therefore need a schema-valid body, otherwise the request fails validation with 400 before
 * authorization is ever consulted.
 */
@WebMvcTest({PatientController.class, DoctorController.class, VisitController.class,
             ReportController.class, AuditLogController.class, RegistrationStatusController.class})
@Import(SecurityConfig.class)
class EndpointAuthorizationMatrixTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private PatientService patientService;
    @MockitoBean private DoctorService doctorService;
    @MockitoBean private VisitService visitService;
    @MockitoBean private InsuranceService insuranceService;
    @MockitoBean private AuditLogService auditLogService;
    @MockitoBean private AccountProvisioningService accountProvisioning;

    @MockitoBean private AuthorizationService authorizationService;

    /** Required by the OAuth2 resource server declared in SecurityConfig. */
    @MockitoBean private JwtDecoder jwtDecoder;

    private static final List<String> ALL_ROLES = List.of("ADMIN", "DOCTOR", "PATIENT");

    private static final String VISIT =
            "{\"patientId\":1,\"doctorId\":1,\"visitDate\":\"2026-01-01\",\"visitTime\":\"09:00:00\"}";
    private static final String DIAGNOSIS =
            "{\"visitId\":1,\"name\":\"Flu\"}";
    private static final String PRESCRIPTION =
            "{\"visitId\":1,\"note\":\"Aspirin\"}";
    private static final String SICK_LEAVE =
            "{\"visitId\":1,\"validFrom\":\"2026-01-01\",\"validForDays\":5}";
    private static final String PATIENT =
            "{\"egn\":\"1234567890\",\"firstName\":\"John\",\"lastName\":\"Doe\"}";
    private static final String DOCTOR =
            "{\"firstName\":\"Ivan\",\"lastName\":\"Petrov\",\"isGP\":true,\"specializationIds\":[]}";
    private static final String SPECIALIZATION =
            "{\"name\":\"Cardiology\"}";
    private static final String INSURANCE =
            "{\"insuranceDate\":\"2026-01-01\",\"paidOn\":\"2026-01-01\"}";

    /** One protected endpoint and the roles the specification says may reach it. */
    private record Endpoint(HttpMethod method, String path, String body, Set<String> allowed) {
        @Override
        public String toString() {
            return method + " " + path + " (allowed: " + String.join("/", allowed.stream().sorted().toList()) + ")";
        }
    }

    private static Endpoint get(String path, String... allowed) {
        return new Endpoint(HttpMethod.GET, path, null, Set.of(allowed));
    }

    private static Endpoint write(HttpMethod method, String path, String body, String... allowed) {
        return new Endpoint(method, path, body, Set.of(allowed));
    }

    static Stream<Endpoint> endpoints() {
        return Stream.of(
                // ── Patients ──────────────────────────────────────────────
                get("/api/patients", "ADMIN", "DOCTOR"),
                get("/api/patients/1", "ADMIN", "DOCTOR", "PATIENT"),
                get("/api/patients/keycloak/abc", "ADMIN", "PATIENT"),
                get("/api/patients/1/insurance", "ADMIN", "DOCTOR", "PATIENT"),
                write(HttpMethod.POST, "/api/patients", PATIENT, "ADMIN"),
                write(HttpMethod.PUT, "/api/patients/1", PATIENT, "ADMIN"),
                write(HttpMethod.DELETE, "/api/patients/1", null, "ADMIN"),
                write(HttpMethod.POST, "/api/patients/1/insurance", INSURANCE, "ADMIN", "DOCTOR"),
                // Note: doctors are excluded here even though the service check they reach would
                // admit them — the controller rule is the narrower of the two.
                write(HttpMethod.PATCH, "/api/patients/1/gp?gpDoctorId=1", null, "ADMIN", "PATIENT"),

                // ── Doctors ───────────────────────────────────────────────
                get("/api/doctors", "ADMIN", "DOCTOR", "PATIENT"),
                get("/api/doctors/1", "ADMIN", "DOCTOR", "PATIENT"),
                get("/api/doctors/keycloak/abc", "ADMIN", "DOCTOR"),
                get("/api/doctors/1/patients", "ADMIN", "DOCTOR"),
                get("/api/doctors/specializations", "ADMIN", "DOCTOR", "PATIENT"),
                write(HttpMethod.POST, "/api/doctors", DOCTOR, "ADMIN"),
                write(HttpMethod.PUT, "/api/doctors/1", DOCTOR, "ADMIN"),
                write(HttpMethod.DELETE, "/api/doctors/1", null, "ADMIN"),
                write(HttpMethod.POST, "/api/doctors/1/approve", null, "ADMIN"),
                write(HttpMethod.POST, "/api/doctors/specializations", SPECIALIZATION, "ADMIN"),

                // ── Visits and clinical documents ─────────────────────────
                get("/api/visits", "ADMIN", "DOCTOR"),
                get("/api/visits/1", "ADMIN", "DOCTOR", "PATIENT"),
                get("/api/visits/patient/1", "ADMIN", "DOCTOR", "PATIENT"),
                write(HttpMethod.POST, "/api/visits", VISIT, "ADMIN", "DOCTOR"),
                write(HttpMethod.PUT, "/api/visits/1", VISIT, "ADMIN", "DOCTOR"),
                write(HttpMethod.DELETE, "/api/visits/1", null, "ADMIN", "DOCTOR"),
                write(HttpMethod.POST, "/api/visits/diagnosis", DIAGNOSIS, "ADMIN", "DOCTOR"),
                write(HttpMethod.PUT, "/api/visits/diagnosis/1", DIAGNOSIS, "ADMIN", "DOCTOR"),
                write(HttpMethod.DELETE, "/api/visits/diagnosis/1", null, "ADMIN", "DOCTOR"),
                write(HttpMethod.POST, "/api/visits/prescription", PRESCRIPTION, "ADMIN", "DOCTOR"),
                write(HttpMethod.POST, "/api/visits/sickleave", SICK_LEAVE, "ADMIN", "DOCTOR"),
                get("/api/visits/diagnosis/1", "ADMIN", "DOCTOR", "PATIENT"),
                get("/api/visits/prescription/1", "ADMIN", "DOCTOR", "PATIENT"),
                get("/api/visits/sickleave/1", "ADMIN", "DOCTOR", "PATIENT"),
                write(HttpMethod.PUT, "/api/visits/prescription/1", PRESCRIPTION, "ADMIN", "DOCTOR"),
                write(HttpMethod.DELETE, "/api/visits/prescription/1", null, "ADMIN", "DOCTOR"),
                write(HttpMethod.PUT, "/api/visits/sickleave/1", SICK_LEAVE, "ADMIN", "DOCTOR"),
                write(HttpMethod.DELETE, "/api/visits/sickleave/1", null, "ADMIN", "DOCTOR"),

                // ── Reports: clinical staff only ──────────────────────────
                get("/api/reports/diagnoses/common", "ADMIN", "DOCTOR"),
                get("/api/reports/doctors/visits", "ADMIN", "DOCTOR"),
                get("/api/reports/doctors/gp-patients", "ADMIN", "DOCTOR"),
                get("/api/reports/sickleaves/months", "ADMIN", "DOCTOR"),
                get("/api/reports/sickleaves/doctors", "ADMIN", "DOCTOR"),
                get("/api/reports/visits/patients", "ADMIN", "DOCTOR"),
                get("/api/reports/diagnoses/names", "ADMIN", "DOCTOR"),
                get("/api/reports/diagnoses/patients?diagnosis=Flu", "ADMIN", "DOCTOR"),
                get("/api/reports/visits/period?start=2026-01-01&end=2026-12-31", "ADMIN", "DOCTOR"),
                get("/api/reports/visits/doctor/period?doctorId=1&start=2026-01-01&end=2026-12-31", "ADMIN", "DOCTOR"),
                get("/api/reports/pending-registrations", "ADMIN"),

                // ── Audit log: administrators only ────────────────────────
                get("/api/audit-logs", "ADMIN"),

                // Reports only on the caller's own subject, so every signed-in role may read it;
                // listed here so the anonymous case is still asserted.
                get("/api/registration-status", "ADMIN", "DOCTOR", "PATIENT")
        );
    }

    private MockHttpServletRequestBuilder request(Endpoint e) {
        MockHttpServletRequestBuilder builder = switch (e.method().name()) {
            case "GET" -> MockMvcRequestBuilders.get(e.path());
            case "POST" -> MockMvcRequestBuilders.post(e.path());
            case "PUT" -> MockMvcRequestBuilders.put(e.path());
            case "PATCH" -> MockMvcRequestBuilders.patch(e.path());
            case "DELETE" -> MockMvcRequestBuilders.delete(e.path());
            default -> throw new IllegalArgumentException("unsupported: " + e.method());
        };
        if (e.body() != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(e.body());
        }
        return builder;
    }

    /** Every role the endpoint does not name must be refused with 403. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void deniesRolesOutsideTheMatrix(Endpoint endpoint) throws Exception {
        for (String role : ALL_ROLES) {
            if (endpoint.allowed().contains(role)) {
                continue;
            }
            mockMvc.perform(request(endpoint).with(user("someone").roles(role)))
                    .andExpect(status().isForbidden());
        }
    }

    /** Nothing under /api is reachable without authentication. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @WithAnonymousUser
    void deniesAnonymous(Endpoint endpoint) throws Exception {
        mockMvc.perform(request(endpoint)).andExpect(status().isUnauthorized());
    }
}
