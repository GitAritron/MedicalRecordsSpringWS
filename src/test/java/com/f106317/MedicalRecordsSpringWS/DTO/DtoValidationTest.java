package com.f106317.MedicalRecordsSpringWS.DTO;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the Bean Validation constraints on the request DTOs.
 * <p>
 * WHY: the field sizes mirror the column widths in db/init.sql. Without them an over-long value
 * reaches the database and comes back as a constraint violation (HTTP 409) instead of a validation
 * failure (HTTP 400), and the caller gets no useful message about which field was wrong.
 */
class DtoValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    private static boolean violates(Object dto, String property) {
        Set<ConstraintViolation<Object>> violations = validator.validate(dto);
        return violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals(property));
    }

    private static String repeat(int n) {
        return "x".repeat(n);
    }

    // ── PatientRequest ─────────────────────────────────────────────────────

    @Test
    void patientRequest_acceptsValidPayload() {
        PatientRequest dto = new PatientRequest(null, "1234567890", "John", "Doe", 1L);
        assertTrue(validator.validate(dto).isEmpty());
    }

    /** patient.keycloak_id is VARCHAR(36). */
    @Test
    void patientRequest_rejectsOverLongKeycloakId() {
        PatientRequest dto = new PatientRequest(repeat(37), "1234567890", "John", "Doe", 1L);
        assertTrue(violates(dto, "keycloakId"));
    }

    @Test
    void patientRequest_rejectsOverLongName() {
        PatientRequest dto = new PatientRequest(null, "1234567890", repeat(101), "Doe", 1L);
        assertTrue(violates(dto, "firstName"));
    }

    @Test
    void patientRequest_rejectsMalformedEgn() {
        assertTrue(violates(new PatientRequest(null, "123", "John", "Doe", 1L), "egn"));
        assertTrue(violates(new PatientRequest(null, "12345678ab", "John", "Doe", 1L), "egn"));
    }

    // ── Self-registration payloads cannot carry privileged fields ──────────

    /**
     * Structural guarantee behind the self-registration split: these records have no keycloakId
     * or isGP component at all, so no request body can supply one.
     */
    @Test
    void selfRegistrationRecordsOmitPrivilegedFields() {
        for (var c : PatientSelfRegistrationRequest.class.getRecordComponents()) {
            assertFalse(c.getName().equals("keycloakId"),
                    "PatientSelfRegistrationRequest must not expose keycloakId");
        }
        for (var c : DoctorSelfRegistrationRequest.class.getRecordComponents()) {
            assertFalse(c.getName().equals("keycloakId"),
                    "DoctorSelfRegistrationRequest must not expose keycloakId");
            assertFalse(c.getName().equalsIgnoreCase("isGP"),
                    "DoctorSelfRegistrationRequest must not expose isGP");
        }
    }

    // ── Diagnosis / Prescription / Specialization sizes ───────────────────

    /** diagnosis.name is VARCHAR(255). */
    @Test
    void diagnosisRequest_rejectsOverLongName() {
        assertTrue(violates(new DiagnosisRequest(1L, repeat(256)), "name"));
        assertTrue(validator.validate(new DiagnosisRequest(1L, repeat(255))).isEmpty());
    }

    @Test
    void diagnosisRequest_rejectsBlankName() {
        assertTrue(violates(new DiagnosisRequest(1L, "   "), "name"));
    }

    /** prescription.note is TEXT, so the cap is an application-level bound. */
    @Test
    void prescriptionRequest_rejectsOverLongNote() {
        assertTrue(violates(new PrescriptionRequest(1L, repeat(4001)), "note"));
        assertTrue(validator.validate(new PrescriptionRequest(1L, repeat(4000))).isEmpty());
    }

    /** specialization.name is VARCHAR(100). */
    @Test
    void specializationRequest_rejectsOverLongName() {
        assertTrue(violates(new SpecializationRequest(repeat(101)), "name"));
    }

    // ── Insurance payment dates ───────────────────────────────────────────

    /**
     * Future-dated contributions must be refused: the coverage check counts months in a window
     * ending today, so a future payment would otherwise manufacture continuous coverage.
     */
    @Test
    void insurancePaymentRequest_rejectsFutureDates() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        assertTrue(violates(new InsurancePaymentRequest(tomorrow, LocalDate.now()), "insuranceDate"));
        assertTrue(violates(new InsurancePaymentRequest(LocalDate.now(), tomorrow), "paidOn"));
    }

    @Test
    void insurancePaymentRequest_acceptsTodayAndPast() {
        assertTrue(validator.validate(
                new InsurancePaymentRequest(LocalDate.now(), LocalDate.now())).isEmpty());
        assertTrue(validator.validate(
                new InsurancePaymentRequest(LocalDate.now().minusMonths(3), LocalDate.now().minusMonths(3)))
                .isEmpty());
    }
}
