package com.f106317.MedicalRecordsSpringWS.DTO;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.AuditLog;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Diagnosis;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Doctor;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Patient;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Prescription;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.SickLeave;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Specialization;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Visit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// fromEntity mappers and withTemporaryPassword. Service tests only see the finished DTO, so a dropped field gets past them.
// Asserted throughout: needed fields survive the mapping, and temporaryPassword stays null unless attached.
class ResponseMappingTest {

    @Nested
    @DisplayName("PatientResponse")
    class PatientMapping {

        @Test
        void keycloakIdSurvivesTheMapping() {
            Patient patient = patient(5L, "kc-ivan", "1234567890", "Ivan", "Ivanov", doctorNamed(9L, "Gregory", "House"));

            PatientResponse response = PatientResponse.fromEntity(patient);

            assertEquals("kc-ivan", response.keycloakId(),
                    "the account binding must not be dropped on the way out");
        }

        @Test
        void everyScalarFieldIsCarriedThrough() {
            Patient patient = patient(5L, "kc-ivan", "1234567890", "Ivan", "Ivanov", doctorNamed(9L, "Gregory", "House"));

            PatientResponse response = PatientResponse.fromEntity(patient);

            assertEquals(5L, response.id());
            assertEquals("1234567890", response.egn());
            assertEquals("Ivan", response.firstName());
            assertEquals("Ivanov", response.lastName());
        }

        @Test
        void generalPractitionerIsFlattenedToIdAndDisplayName() {
            Patient patient = patient(5L, "kc-ivan", "1234567890", "Ivan", "Ivanov", doctorNamed(9L, "Gregory", "House"));

            PatientResponse response = PatientResponse.fromEntity(patient);

            assertEquals(9L, response.gpDoctorId());
            assertEquals("Dr. Gregory House", response.gpDoctorName());
        }

        // gp is null mid-registration - the mapper must give nulls, not throw.
        @Test
        void aPatientWithNoGpMapsToNullsRatherThanThrowing() {
            Patient patient = patient(5L, "kc-ivan", "1234567890", "Ivan", "Ivanov", null);

            PatientResponse response = PatientResponse.fromEntity(patient);

            assertNull(response.gpDoctorId());
            assertNull(response.gpDoctorName());
        }

        @Test
        void temporaryPasswordIsNullOnAnOrdinaryRead() {
            Patient patient = patient(5L, "kc-ivan", "1234567890", "Ivan", "Ivanov", null);

            assertNull(PatientResponse.fromEntity(patient).temporaryPassword(),
                    "a list or detail read must never carry a credential");
        }

        @Test
        void withTemporaryPasswordKeepsEveryOtherFieldIncludingKeycloakId() {
            Patient patient = patient(5L, "kc-ivan", "1234567890", "Ivan", "Ivanov", doctorNamed(9L, "Gregory", "House"));
            PatientResponse original = PatientResponse.fromEntity(patient);

            PatientResponse withPassword = original.withTemporaryPassword("s3cret");

            assertEquals("s3cret", withPassword.temporaryPassword());
            assertEquals(original.id(), withPassword.id());
            assertEquals(original.keycloakId(), withPassword.keycloakId());
            assertEquals(original.egn(), withPassword.egn());
            assertEquals(original.firstName(), withPassword.firstName());
            assertEquals(original.lastName(), withPassword.lastName());
            assertEquals(original.gpDoctorId(), withPassword.gpDoctorId());
            assertEquals(original.gpDoctorName(), withPassword.gpDoctorName());
        }
    }

    @Nested
    @DisplayName("DoctorResponse")
    class DoctorMapping {

        @Test
        void scalarFieldsAndTheGpFlagAreCarriedThrough() {
            Doctor doctor = doctorNamed(9L, "Gregory", "House");
            doctor.setKeycloakId("kc-house");
            doctor.setGP(true);

            DoctorResponse response = DoctorResponse.fromEntity(doctor);

            assertEquals(9L, response.id());
            assertEquals("kc-house", response.keycloakId());
            assertEquals("Gregory", response.firstName());
            assertEquals("House", response.lastName());
            assertTrue(response.isGP());
        }

        @Test
        void specializationsAreMappedElementByElement() {
            Doctor doctor = doctorNamed(9L, "Gregory", "House");
            doctor.setSpecializations(new LinkedHashSet<>(java.util.List.of(
                    specialization(1L, "Cardiology"),
                    specialization(2L, "Neurology"))));

            DoctorResponse response = DoctorResponse.fromEntity(doctor);

            assertEquals(2, response.specializations().size());
            assertTrue(response.specializations().stream()
                    .anyMatch(s -> s.id().equals(1L) && s.name().equals("Cardiology")));
        }

        @Test
        void noSpecializationsBecomesAnEmptyListNotNull() {
            Doctor doctor = doctorNamed(9L, "Robert", "Chase");

            DoctorResponse response = DoctorResponse.fromEntity(doctor);

            assertNotNull(response.specializations());
            assertTrue(response.specializations().isEmpty());
        }

        @Test
        void nullSpecializationsIsToleratedRatherThanThrowing() {
            Doctor doctor = doctorNamed(9L, "Robert", "Chase");
            doctor.setSpecializations(null);

            DoctorResponse response = DoctorResponse.fromEntity(doctor);

            assertNotNull(response.specializations());
            assertTrue(response.specializations().isEmpty());
        }

        @Test
        void temporaryPasswordIsNullOnAnOrdinaryRead() {
            assertNull(DoctorResponse.fromEntity(doctorNamed(9L, "Gregory", "House")).temporaryPassword());
        }

        @Test
        void withTemporaryPasswordKeepsEveryOtherFieldIncludingSpecializations() {
            Doctor doctor = doctorNamed(9L, "Gregory", "House");
            doctor.setKeycloakId("kc-house");
            doctor.setGP(true);
            doctor.setSpecializations(new LinkedHashSet<>(java.util.List.of(specialization(1L, "Cardiology"))));
            DoctorResponse original = DoctorResponse.fromEntity(doctor);

            DoctorResponse withPassword = original.withTemporaryPassword("s3cret");

            assertEquals("s3cret", withPassword.temporaryPassword());
            assertEquals(original.id(), withPassword.id());
            assertEquals(original.keycloakId(), withPassword.keycloakId());
            assertEquals(original.isGP(), withPassword.isGP());
            assertEquals(original.specializations(), withPassword.specializations());
        }
    }

    @Nested
    @DisplayName("VisitResponse")
    class VisitMapping {

        @Test
        void patientAndDoctorAreFlattenedToIdAndFullName() {
            VisitResponse response = VisitResponse.fromEntity(fullVisit());

            assertEquals(5L, response.patientId());
            assertEquals("Ivan Ivanov", response.patientName());
            assertEquals(9L, response.doctorId());
            assertEquals("Gregory House", response.doctorName());
        }

        @Test
        void dateAndTimeAreCarriedThrough() {
            VisitResponse response = VisitResponse.fromEntity(fullVisit());

            assertEquals(LocalDate.of(2026, 3, 10), response.visitDate());
            assertEquals(LocalTime.of(9, 0), response.visitTime());
        }

        @Test
        void nestedCollectionsAreMappedNotDropped() {
            VisitResponse response = VisitResponse.fromEntity(fullVisit());

            assertEquals(2, response.diagnoses().size());
            assertEquals(1, response.prescriptions().size());
            assertTrue(response.diagnoses().stream().anyMatch(d -> d.name().equals("Flu")));
            assertEquals("Rest", response.prescriptions().get(0).note());
        }

        @Test
        void aVisitWithNoSickLeaveMapsToNull() {
            Visit visit = fullVisit();
            visit.setSickLeave(null);

            assertNull(VisitResponse.fromEntity(visit).sickLeave());
        }

        @Test
        void aVisitWithSickLeaveMapsItInFull() {
            SickLeaveResponse sickLeave = VisitResponse.fromEntity(fullVisit()).sickLeave();

            assertNotNull(sickLeave);
            assertEquals(LocalDate.of(2026, 3, 10), sickLeave.validFrom());
            assertEquals(5, sickLeave.validForDays());
        }

        @Test
        void emptyCollectionsMapToEmptyListsNotNull() {
            Visit visit = fullVisit();
            visit.getDiagnoses().clear();
            visit.getPrescriptions().clear();

            VisitResponse response = VisitResponse.fromEntity(visit);

            assertNotNull(response.diagnoses());
            assertNotNull(response.prescriptions());
            assertTrue(response.diagnoses().isEmpty());
            assertTrue(response.prescriptions().isEmpty());
        }

        @Test
        void nullCollectionsAreToleratedRatherThanThrowing() {
            Visit visit = fullVisit();
            visit.setDiagnoses(null);
            visit.setPrescriptions(null);

            VisitResponse response = VisitResponse.fromEntity(visit);

            assertTrue(response.diagnoses().isEmpty());
            assertTrue(response.prescriptions().isEmpty());
        }
    }

    @Nested
    @DisplayName("Child response mappers")
    class ChildMapping {

        @Test
        void diagnosisCarriesItsOwningVisitId() {
            Visit visit = visitShell();
            Diagnosis diagnosis = new Diagnosis();
            diagnosis.setId(3L);
            diagnosis.setName("Flu");
            diagnosis.setVisit(visit);

            DiagnosisResponse response = DiagnosisResponse.fromEntity(diagnosis);

            assertEquals(3L, response.id());
            assertEquals(1L, response.visitId());
            assertEquals("Flu", response.name());
        }

        @Test
        void prescriptionCarriesItsOwningVisitId() {
            Visit visit = visitShell();
            Prescription prescription = new Prescription();
            prescription.setId(4L);
            prescription.setNote("Rest");
            prescription.setVisit(visit);

            PrescriptionResponse response = PrescriptionResponse.fromEntity(prescription);

            assertEquals(4L, response.id());
            assertEquals(1L, response.visitId());
            assertEquals("Rest", response.note());
        }

        @Test
        void sickLeaveCarriesItsOwningVisitId() {
            Visit visit = visitShell();
            SickLeave sickLeave = new SickLeave();
            sickLeave.setId(6L);
            sickLeave.setValidFrom(LocalDate.of(2026, 3, 10));
            sickLeave.setValidForDays(5);
            sickLeave.setVisit(visit);

            SickLeaveResponse response = SickLeaveResponse.fromEntity(sickLeave);

            assertEquals(6L, response.id());
            assertEquals(1L, response.visitId());
            assertEquals(LocalDate.of(2026, 3, 10), response.validFrom());
            assertEquals(5, response.validForDays());
        }

        @Test
        void specializationMapsIdAndName() {
            SpecializationResponse response = SpecializationResponse.fromEntity(specialization(1L, "Cardiology"));

            assertEquals(1L, response.id());
            assertEquals("Cardiology", response.name());
        }
    }

    @Nested
    @DisplayName("AuditLogResponse")
    class AuditMapping {

        @Test
        void everyFieldIncludingTheTimestampIsCarriedThrough() {
            LocalDateTime when = LocalDateTime.of(2026, 1, 1, 8, 0);
            AuditLog log = new AuditLog();
            log.setId(2L);
            log.setActor("admin");
            log.setAction("APPROVE_DOCTOR");
            log.setEntityType("Doctor");
            log.setEntityId(9L);
            log.setEntityLabel("Dr. Gregory House");
            log.setCreatedAt(when);

            AuditLogResponse response = AuditLogResponse.fromEntity(log);

            assertEquals(2L, response.id());
            assertEquals("admin", response.actor());
            assertEquals("APPROVE_DOCTOR", response.action());
            assertEquals("Doctor", response.entityType());
            assertEquals(9L, response.entityId());
            assertEquals("Dr. Gregory House", response.entityLabel());
            assertEquals(when, response.createdAt());
        }

        // entityLabel is the only nullable column.
        @Test
        void aMissingEntityLabelStaysNull() {
            AuditLog log = new AuditLog();
            log.setId(2L);
            log.setActor("admin");
            log.setAction("DELETE");
            log.setEntityType("Patient");
            log.setEntityId(5L);
            log.setCreatedAt(LocalDateTime.of(2026, 1, 1, 8, 0));

            assertNull(AuditLogResponse.fromEntity(log).entityLabel());
        }
    }

    // ──────────────────────────────────────────────
    // Fixture helpers
    // ──────────────────────────────────────────────

    private static Doctor doctorNamed(Long id, String firstName, String lastName) {
        Doctor d = new Doctor();
        d.setId(id);
        d.setFirstName(firstName);
        d.setLastName(lastName);
        return d;
    }

    private static Patient patient(Long id, String keycloakId, String egn, String firstName,
                                   String lastName, Doctor gp) {
        Patient p = new Patient();
        p.setId(id);
        p.setKeycloakId(keycloakId);
        p.setEgn(egn);
        p.setFirstName(firstName);
        p.setLastName(lastName);
        p.setGp(gp);
        return p;
    }

    private static Specialization specialization(Long id, String name) {
        Specialization s = new Specialization();
        s.setId(id);
        s.setName(name);
        return s;
    }

    private static Visit visitShell() {
        Visit v = new Visit();
        v.setId(1L);
        v.setPatient(patient(5L, "kc-ivan", "1234567890", "Ivan", "Ivanov", null));
        v.setDoctor(doctorNamed(9L, "Gregory", "House"));
        v.setVisitDate(LocalDate.of(2026, 3, 10));
        v.setVisitTime(LocalTime.of(9, 0));
        return v;
    }

    private static Visit fullVisit() {
        Visit v = visitShell();

        Diagnosis flu = new Diagnosis();
        flu.setId(3L);
        flu.setName("Flu");
        flu.setVisit(v);
        Diagnosis cough = new Diagnosis();
        cough.setId(31L);
        cough.setName("Cough");
        cough.setVisit(v);
        v.setDiagnoses(new LinkedHashSet<>(java.util.List.of(flu, cough)));

        Prescription rest = new Prescription();
        rest.setId(4L);
        rest.setNote("Rest");
        rest.setVisit(v);
        v.setPrescriptions(new LinkedHashSet<>(java.util.List.of(rest)));

        SickLeave sickLeave = new SickLeave();
        sickLeave.setId(6L);
        sickLeave.setValidFrom(LocalDate.of(2026, 3, 10));
        sickLeave.setValidForDays(5);
        sickLeave.setVisit(v);
        v.setSickLeave(sickLeave);

        return v;
    }
}
