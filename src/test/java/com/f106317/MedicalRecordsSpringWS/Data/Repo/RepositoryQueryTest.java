package com.f106317.MedicalRecordsSpringWS.Data.Repo;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.AuditLog;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Diagnosis;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Doctor;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.PaidInsurance;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Patient;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Prescription;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.SickLeave;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Specialization;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Visit;
import com.f106317.MedicalRecordsSpringWS.DTO.ReportDTO;
import org.hibernate.Hibernate;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executes every repository query against a real database.
 *
 * WHY this class exists: the rest of the suite mocks all nine repositories, so the twelve JPQL
 * statements and the seven {@code @EntityGraph} fetch plans were never run — only parsed at
 * context startup. That is precisely how M1 survived into a demo: a fetch plan that pulls two
 * collections at once compiles fine and throws MultipleBagFetchException the moment it executes.
 * Mocked repositories cannot catch that class of defect, because the mock returns whatever the
 * test told it to.
 *
 * WHY H2 in MySQL mode rather than plain H2: production is MySQL 8, and MODE=MySQL keeps type
 * names (TEXT on Prescription.note) and function semantics closer to it. This is still not MySQL
 * — see TEST_PLAN.md for the pass that uses the real thing.
 *
 * The overrides below are required: src/main/resources/application.yml pins ddl-auto to validate
 * and the dialect to MySQL, which a @DataJpaTest slice would otherwise inherit and fail on.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:repoquerytest;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
class RepositoryQueryTest {

    @Autowired private TestEntityManager em;
    @Autowired private VisitRepository visitRepository;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private DiagnosisRepository diagnosisRepository;
    @Autowired private SickLeaveRepository sickLeaveRepository;
    @Autowired private PaidInsuranceRepository paidInsuranceRepository;
    @Autowired private AuditLogRepository auditLogRepository;

    private Doctor house;   // GP, 2 visits, 2 specializations
    private Doctor wilson;  // GP, 2 visits, 1 specialization
    private Doctor chase;   // not a GP, no visits, no specializations
    private Patient ivan;
    private Patient maria;
    private Patient petar;
    private Visit marchIvan;    // 2 diagnoses + 2 prescriptions + sick leave  <- the M1 shape
    private Visit marchMaria;
    private Visit juneIvan;
    private Visit januaryPetar;

    @BeforeEach
    void seed() {
        Specialization cardiology = specialization("Cardiology");
        Specialization neurology = specialization("Neurology");
        Specialization oncology = specialization("Oncology");

        house = doctor("Gregory", "House", true, "kc-house", Set.of(cardiology, neurology));
        wilson = doctor("James", "Wilson", true, "kc-wilson", Set.of(oncology));
        chase = doctor("Robert", "Chase", false, "kc-chase", Set.of());

        ivan = patient("Ivan", "Ivanov", "1234567890", "kc-ivan", house);
        maria = patient("Maria", "Petrova", "0987654321", "kc-maria", house);
        petar = patient("Petar", "Georgiev", "1111111111", null, wilson);

        marchIvan = visit(ivan, house, LocalDate.of(2026, 3, 10), LocalTime.of(9, 0));
        addDiagnosis(marchIvan, "Flu");
        addDiagnosis(marchIvan, "Cough");
        addPrescription(marchIvan, "Rest for five days");
        addPrescription(marchIvan, "Fluids");
        addSickLeave(marchIvan, LocalDate.of(2026, 3, 10), 5);

        marchMaria = visit(maria, house, LocalDate.of(2026, 3, 10), LocalTime.of(10, 0));
        addDiagnosis(marchMaria, "Flu");

        juneIvan = visit(ivan, wilson, LocalDate.of(2026, 6, 15), LocalTime.of(11, 0));
        addDiagnosis(juneIvan, "Migraine");
        addPrescription(juneIvan, "Painkillers");
        addSickLeave(juneIvan, LocalDate.of(2026, 6, 15), 3);

        januaryPetar = visit(petar, wilson, LocalDate.of(2026, 1, 5), LocalTime.of(8, 30));
        addDiagnosis(januaryPetar, "Flu");

        em.persist(marchIvan);
        em.persist(marchMaria);
        em.persist(juneIvan);
        em.persist(januaryPetar);

        // insuranceDate is always the first of the covered month - InsuranceService normalises it,
        // and test-data.sql seeds it the same way. Ivan covers Jan, Feb and Mar, plus a July
        // payment that sits outside the windows exercised below. paidOn is the real payment date,
        // deliberately not the first, to keep the two columns distinguishable.
        insurance(ivan, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 15));
        insurance(ivan, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 20));
        insurance(ivan, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 5));
        insurance(ivan, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1));
        insurance(maria, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 10));

        audit("admin", "APPROVE_DOCTOR", "Doctor", house.getId(), LocalDateTime.of(2026, 1, 1, 8, 0));
        audit("admin", "APPROVE_DOCTOR", "Doctor", wilson.getId(), LocalDateTime.of(2026, 1, 2, 8, 0));
        audit("admin", "DELETE", "Patient", petar.getId(), LocalDateTime.of(2026, 1, 3, 8, 0));

        // Detach everything. Without this the persistence context would satisfy every association
        // from memory and the @EntityGraph assertions below would pass even with no fetch plan.
        em.flush();
        em.clear();
    }

    @Nested
    @DisplayName("VisitRepository @EntityGraph - M1 regression")
    class VisitFetchPlans {

        /**
         * M1: the fetch plan names diagnoses and prescriptions together. Hibernate rejects two
         * eagerly-fetched *bags* in one query with MultipleBagFetchException, which is why both
         * are mapped as Set. Change either to List and this test fails - which is the point.
         */
        @Test
        void findAllFetchesTwoCollectionsWithoutMultipleBagFetch() {
            List<Visit> visits = assertDoesNotThrow(() -> visitRepository.findAll());
            assertEquals(4, visits.size());

            Visit loaded = visits.stream()
                    .filter(v -> v.getId().equals(marchIvan.getId()))
                    .findFirst()
                    .orElseThrow();

            assertTrue(Hibernate.isInitialized(loaded.getDiagnoses()), "diagnoses should be fetched");
            assertTrue(Hibernate.isInitialized(loaded.getPrescriptions()), "prescriptions should be fetched");
            assertTrue(Hibernate.isInitialized(loaded.getPatient()), "patient should be fetched");
            assertTrue(Hibernate.isInitialized(loaded.getDoctor()), "doctor should be fetched");
            assertEquals(2, loaded.getDiagnoses().size());
            assertEquals(2, loaded.getPrescriptions().size());
            assertNotNull(loaded.getSickLeave());
        }

        @Test
        void findByPatientIdAppliesTheSameFetchPlan() {
            List<Visit> visits = assertDoesNotThrow(() -> visitRepository.findByPatientId(ivan.getId()));
            assertEquals(2, visits.size());
            visits.forEach(v -> {
                assertTrue(Hibernate.isInitialized(v.getDiagnoses()));
                assertTrue(Hibernate.isInitialized(v.getPrescriptions()));
            });
        }

        /**
         * The two period reports render exactly what {@code findAll} renders — VisitResponse walks
         * patient, doctor, diagnoses, prescriptions and sick leave for every row — so they need the
         * same fetch plan. They were written without one, and each row then cost five extra selects.
         * Adding {@code @EntityGraph} to a {@code @Query} is also where MultipleBagFetchException
         * would reappear, so these run the plan rather than trusting it parses.
         */
        @Test
        void findVisitsInPeriodAppliesTheFetchPlan() {
            List<Visit> march = assertDoesNotThrow(() -> visitRepository.findVisitsInPeriod(
                    LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)));
            assertEquals(2, march.size());
            march.forEach(v -> {
                assertTrue(Hibernate.isInitialized(v.getPatient()), "patient should be fetched");
                assertTrue(Hibernate.isInitialized(v.getDoctor()), "doctor should be fetched");
                assertTrue(Hibernate.isInitialized(v.getDiagnoses()), "diagnoses should be fetched");
                assertTrue(Hibernate.isInitialized(v.getPrescriptions()), "prescriptions should be fetched");
            });
        }

        @Test
        void findVisitsByDoctorInPeriodAppliesTheFetchPlan() {
            List<Visit> housesYear = assertDoesNotThrow(() -> visitRepository.findVisitsByDoctorInPeriod(
                    house.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
            assertEquals(2, housesYear.size());
            housesYear.forEach(v -> {
                assertTrue(Hibernate.isInitialized(v.getPatient()));
                assertTrue(Hibernate.isInitialized(v.getDoctor()));
                assertTrue(Hibernate.isInitialized(v.getDiagnoses()));
                assertTrue(Hibernate.isInitialized(v.getPrescriptions()));
            });
        }

        /**
         * A join fetch across two collections multiplies rows in the result set. If the query ever
         * loses its DISTINCT-equivalent semantics, the same visit comes back more than once.
         */
        @Test
        void multiCollectionFetchDoesNotDuplicateRows() {
            List<Visit> visits = visitRepository.findAll();
            long distinctIds = visits.stream().map(Visit::getId).distinct().count();
            assertEquals(visits.size(), distinctIds, "the fetch join must not duplicate visits");
        }
    }

    @Nested
    @DisplayName("VisitRepository queries")
    class VisitQueries {

        @Test
        void findVisitsInPeriodReturnsOnlyThatPeriod() {
            List<Visit> march = visitRepository.findVisitsInPeriod(
                    LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
            assertEquals(2, march.size());
        }

        @Test
        void findVisitsInPeriodIsInclusiveOnBothBounds() {
            List<Visit> singleDay = visitRepository.findVisitsInPeriod(
                    LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 10));
            assertEquals(2, singleDay.size(), "BETWEEN must include both endpoints");
        }

        @Test
        void findVisitsByDoctorInPeriodFiltersByDoctor() {
            List<Visit> housesYear = visitRepository.findVisitsByDoctorInPeriod(
                    house.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
            assertEquals(2, housesYear.size());

            List<Visit> housesJanuary = visitRepository.findVisitsByDoctorInPeriod(
                    house.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
            assertTrue(housesJanuary.isEmpty());
        }

        @Test
        void countByDoctorIdCountsOnlyThatDoctor() {
            assertEquals(2, visitRepository.countByDoctorId(house.getId()));
            assertEquals(2, visitRepository.countByDoctorId(wilson.getId()));
            assertEquals(0, visitRepository.countByDoctorId(chase.getId()));
        }

        @Test
        void slotExistenceChecksMatchOnDateAndTimeTogether() {
            assertTrue(visitRepository.existsByDoctorIdAndVisitDateAndVisitTime(
                    house.getId(), LocalDate.of(2026, 3, 10), LocalTime.of(9, 0)));
            assertFalse(visitRepository.existsByDoctorIdAndVisitDateAndVisitTime(
                    house.getId(), LocalDate.of(2026, 3, 10), LocalTime.of(9, 30)));
            assertTrue(visitRepository.existsByPatientIdAndVisitDateAndVisitTime(
                    ivan.getId(), LocalDate.of(2026, 3, 10), LocalTime.of(9, 0)));
            assertFalse(visitRepository.existsByPatientIdAndVisitDateAndVisitTime(
                    maria.getId(), LocalDate.of(2026, 3, 10), LocalTime.of(9, 0)));
        }
    }

    @Nested
    @DisplayName("PaidInsuranceRepository - M3 six-month window")
    class InsuranceQueries {

        /**
         * M3: the window had no upper bound, so payments dated in the future counted toward the
         * six-month check. The July payment below is what that defect used to let through.
         */
        @Test
        void coveredMonthKeysExcludePaymentsAfterTheWindow() {
            List<Integer> keys = paidInsuranceRepository.findCoveredMonthKeys(
                    ivan.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
            assertEquals(List.of(202601, 202602, 202603), keys.stream().sorted().toList());
            assertFalse(keys.contains(202607), "a July payment is outside a January-March window");
        }

        @Test
        void oneMonthYieldsExactlyOneKey() {
            List<Integer> keys = paidInsuranceRepository.findCoveredMonthKeys(
                    ivan.getId(), LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));
            assertEquals(1, keys.size());
            assertEquals(202602, keys.get(0));
        }

        @Test
        void coveredMonthKeysAreScopedToOnePatient() {
            List<Integer> keys = paidInsuranceRepository.findCoveredMonthKeys(
                    maria.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
            assertEquals(List.of(202602), keys);
        }

        @Test
        void windowBoundsAreInclusive() {
            List<Integer> keys = paidInsuranceRepository.findCoveredMonthKeys(
                    ivan.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1));
            assertEquals(List.of(202601), keys, "a payment exactly on both bounds must count");
        }

        @Test
        void allPatientsVariantReturnsPatientIdWithMonthKey() {
            List<Object[]> rows = paidInsuranceRepository.findCoveredMonthKeysForAllPatients(
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
            assertEquals(4, rows.size(), "Ivan contributes three months, Maria one");
            rows.forEach(row -> {
                assertEquals(2, row.length);
                assertNotNull(row[0]);
                assertNotNull(row[1]);
            });
            long ivanRows = rows.stream()
                    .filter(row -> ((Number) row[0]).longValue() == ivan.getId())
                    .count();
            assertEquals(3, ivanRows);
        }
    }

    /**
     * These rules live in init.sql and, until the entities declared them too, existed nowhere the
     * test schema could see — so nothing could prove they were enforced. The @Table and @Check
     * declarations on PaidInsurance and SickLeave are what make these two tests possible at all.
     */
    @Nested
    @DisplayName("Constraints mirrored from init.sql")
    class SchemaConstraints {

        /**
         * Because InsuranceService normalises insuranceDate to the first of the covered month,
         * this constraint is what enforces "one payment per month per patient". It is the database
         * half of the rule; the service half is the existsBy check that produces a readable message.
         */
        @Test
        void aPatientCannotBePaidUpTwiceForTheSameMonth() {
            PaidInsurance duplicate = new PaidInsurance();
            duplicate.setPatient(em.find(Patient.class, ivan.getId()));
            duplicate.setInsuranceDate(LocalDate.of(2026, 1, 1));    // January already covered
            duplicate.setPaidOn(LocalDate.of(2026, 1, 28));          // a different real payment date

            // persist, not flush, is what raises it: id is GenerationType.IDENTITY, so Hibernate
            // must issue the INSERT immediately to learn the generated key.
            assertThrows(ConstraintViolationException.class, () -> {
                em.persist(duplicate);
                em.flush();
            }, "uq_patient_insurance_date must reject a second payment covering the same month");
        }

        @Test
        void adjacentMonthsAreUnaffected() {
            PaidInsurance april = new PaidInsurance();
            april.setPatient(em.find(Patient.class, ivan.getId()));
            april.setInsuranceDate(LocalDate.of(2026, 4, 1));        // not yet covered
            april.setPaidOn(LocalDate.of(2026, 4, 9));

            assertDoesNotThrow(() -> {
                em.persist(april);
                em.flush();
            }, "the constraint is per covered month, not per patient");
        }

        @Test
        void twoPatientsMayCoverTheSameMonth() {
            PaidInsurance mariasJanuary = new PaidInsurance();
            mariasJanuary.setPatient(em.find(Patient.class, maria.getId()));
            mariasJanuary.setInsuranceDate(LocalDate.of(2026, 1, 1)); // Ivan has January, Maria does not
            mariasJanuary.setPaidOn(LocalDate.of(2026, 1, 11));

            assertDoesNotThrow(() -> {
                em.persist(mariasJanuary);
                em.flush();
            }, "the constraint is scoped to one patient");
        }

        @Test
        void sickLeaveLongerThanAYearIsRejected() {
            Visit visit = em.find(Visit.class, marchMaria.getId());
            SickLeave tooLong = new SickLeave();
            tooLong.setVisit(visit);
            tooLong.setValidFrom(LocalDate.of(2026, 3, 10));
            tooLong.setValidForDays(400);

            assertThrows(ConstraintViolationException.class, () -> {
                em.persist(tooLong);
                em.flush();
            }, "chk_valid_for_days bounds a sick leave to 365 days");
        }

        @Test
        void aVisitCannotCarryTwoSickLeaves() {
            SickLeave second = new SickLeave();
            second.setVisit(em.find(Visit.class, marchIvan.getId()));   // already has one
            second.setValidFrom(LocalDate.of(2026, 3, 11));
            second.setValidForDays(2);

            assertThrows(ConstraintViolationException.class, () -> {
                em.persist(second);
                em.flush();
            }, "the unique constraint Hibernate derives from @OneToOne must keep this 1:1");
        }
    }

    @Nested
    @DisplayName("DoctorRepository")
    class DoctorQueries {

        @Test
        void findAllFetchesSpecializations() {
            List<Doctor> doctors = doctorRepository.findAll();
            assertEquals(3, doctors.size());
            doctors.forEach(d -> assertTrue(Hibernate.isInitialized(d.getSpecializations()),
                    "the fetch plan should initialise specializations"));
            Doctor loadedHouse = doctors.stream()
                    .filter(d -> d.getId().equals(house.getId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(2, loadedHouse.getSpecializations().size());
        }

        @Test
        void findByKeycloakIdResolvesTheAccountBinding() {
            assertTrue(doctorRepository.findByKeycloakId("kc-house").isPresent());
            assertTrue(doctorRepository.findByKeycloakId("kc-nobody").isEmpty());
        }

        /**
         * A LEFT JOIN, so a doctor with no visits must still appear with a count of zero. An inner
         * join would silently drop Dr Chase from the report.
         */
        @Test
        void visitCountIncludesDoctorsWithNoVisits() {
            List<ReportDTO.DoctorVisitCount> rows = doctorRepository.findDoctorsWithVisitCount();
            assertEquals(3, rows.size());

            long chaseCount = rows.stream()
                    .filter(row -> row.doctorId().equals(chase.getId()))
                    .map(ReportDTO.DoctorVisitCount::visitCount)
                    .findFirst()
                    .orElseThrow();
            assertEquals(0L, chaseCount);

            assertTrue(rows.get(0).visitCount() >= rows.get(rows.size() - 1).visitCount(),
                    "rows should be ordered by visit count descending");
            assertEquals("Robert Chase", rows.stream()
                    .filter(row -> row.doctorId().equals(chase.getId()))
                    .findFirst().orElseThrow().doctorName(),
                    "the name is built in SQL by CONCAT, not by the caller");
        }

        @Test
        void gpPatientCountExcludesNonGpDoctors() {
            List<ReportDTO.GpDoctorPatientCount> rows = doctorRepository.findGpDoctorsWithPatientCount();
            assertEquals(2, rows.size(), "Dr Chase is not a GP and must not appear");
            assertTrue(rows.stream().noneMatch(row -> row.doctorId().equals(chase.getId())));

            long housePatients = rows.stream()
                    .filter(row -> row.doctorId().equals(house.getId()))
                    .map(ReportDTO.GpDoctorPatientCount::patientCount)
                    .findFirst()
                    .orElseThrow();
            assertEquals(2L, housePatients);
        }
    }

    @Nested
    @DisplayName("PatientRepository")
    class PatientQueries {

        @Test
        void findAllFetchesTheGeneralPractitioner() {
            List<Patient> patients = patientRepository.findAll();
            assertEquals(3, patients.size());
            patients.forEach(p -> assertTrue(Hibernate.isInitialized(p.getGp()),
                    "the fetch plan should initialise the gp association"));
        }

        @Test
        void findByGpIdReturnsOnlyThatGpsPatients() {
            List<Patient> housesPatients = patientRepository.findByGpId(house.getId());
            assertEquals(2, housesPatients.size());
            housesPatients.forEach(p -> assertTrue(Hibernate.isInitialized(p.getGp())));

            assertEquals(1, patientRepository.findByGpId(wilson.getId()).size());
            assertTrue(patientRepository.findByGpId(chase.getId()).isEmpty());
        }

        @Test
        void egnUniquenessCheckIsExact() {
            assertTrue(patientRepository.existsByEgn("1234567890"));
            assertFalse(patientRepository.existsByEgn("9999999999"));
        }

        @Test
        void findByKeycloakIdIgnoresPatientsWithNoAccount() {
            Optional<Patient> found = patientRepository.findByKeycloakId("kc-ivan");
            assertTrue(found.isPresent());
            assertEquals("Ivan", found.get().getFirstName());
            assertTrue(patientRepository.findByKeycloakId("kc-nobody").isEmpty());
        }
    }

    @Nested
    @DisplayName("DiagnosisRepository")
    class DiagnosisQueries {

        @Test
        void findPatientsByDiagnosisNameReturnsEachPatientOnce() {
            List<Patient> withFlu = diagnosisRepository.findPatientsByDiagnosisName("Flu");
            assertEquals(3, withFlu.size());
            assertEquals(3, withFlu.stream().map(Patient::getId).distinct().count());

            assertEquals(1, diagnosisRepository.findPatientsByDiagnosisName("Migraine").size());
            assertTrue(diagnosisRepository.findPatientsByDiagnosisName("Nothing").isEmpty());
        }

        @Test
        void mostCommonDiagnosesAreOrderedByFrequency() {
            List<ReportDTO.DiagnosisCount> rows = diagnosisRepository.findMostCommonDiagnoses();
            assertEquals(3, rows.size());
            assertEquals("Flu", rows.get(0).diagnosisName());
            assertEquals(3L, rows.get(0).count());
        }

        @Test
        void distinctNamesAreDeduplicatedAndSorted() {
            List<String> names = diagnosisRepository.findDistinctDiagnosisNames();
            assertEquals(List.of("Cough", "Flu", "Migraine"), names);
        }
    }

    @Nested
    @DisplayName("SickLeaveRepository")
    class SickLeaveQueries {

        @Test
        void monthsWithMostSickLeavesGroupsByYearAndMonth() {
            List<ReportDTO.SickLeaveMonthCount> rows = sickLeaveRepository.findMonthsWithMostSickLeaves();
            assertEquals(2, rows.size(), "March and June each have one sick leave");
            rows.forEach(row -> {
                assertEquals(2026, row.year());
                assertEquals(1L, row.count());
            });
            assertTrue(rows.stream().anyMatch(r -> r.month() == 3), "March");
            assertTrue(rows.stream().anyMatch(r -> r.month() == 6), "June");
        }

        @Test
        void doctorsWithMostSickLeavesTraversesVisitToDoctor() {
            List<ReportDTO.DoctorSickLeaveCount> rows = sickLeaveRepository.findDoctorsWithMostSickLeaves();
            assertEquals(2, rows.size());
            rows.forEach(row -> {
                assertNotNull(row.doctorId());
                assertNotNull(row.doctorName());
                assertEquals(1L, row.sickLeaveCount());
            });
        }
    }

    @Nested
    @DisplayName("AuditLogRepository")
    class AuditQueries {

        @Test
        void pagedFeedIsNewestFirst() {
            Page<AuditLog> page = auditLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 2));
            assertEquals(3, page.getTotalElements());
            assertEquals(2, page.getContent().size());
            assertTrue(page.getContent().get(0).getCreatedAt()
                    .isAfter(page.getContent().get(1).getCreatedAt()));
        }

        @Test
        void filteringByActionAndEntityTypeExcludesOtherActions() {
            List<AuditLog> approvals =
                    auditLogRepository.findByActionAndEntityTypeOrderByCreatedAtDesc("APPROVE_DOCTOR", "Doctor");
            assertEquals(2, approvals.size());
            approvals.forEach(log -> assertEquals("APPROVE_DOCTOR", log.getAction()));
        }

        /**
         * J1: this set is what removes an approved doctor from the pending list. If it ever
         * returned entity ids for the wrong action, approved doctors would reappear as pending.
         */
        @Test
        void approvedEntityIdsAreTheApprovedDoctorsOnly() {
            Set<Long> approved =
                    auditLogRepository.findEntityIdsByActionAndEntityType("APPROVE_DOCTOR", "Doctor");
            assertEquals(Set.of(house.getId(), wilson.getId()), approved);
            assertFalse(approved.contains(petar.getId()));
        }

        @Test
        void unknownActionYieldsAnEmptySet() {
            assertTrue(auditLogRepository
                    .findEntityIdsByActionAndEntityType("NO_SUCH_ACTION", "Doctor").isEmpty());
        }
    }

    // ---------------------------------------------------------------- fixture helpers

    private Specialization specialization(String name) {
        Specialization s = new Specialization();
        s.setName(name);
        return em.persist(s);
    }

    private Doctor doctor(String first, String last, boolean gp, String keycloakId,
                          Set<Specialization> specializations) {
        Doctor d = new Doctor();
        d.setFirstName(first);
        d.setLastName(last);
        d.setGP(gp);
        d.setKeycloakId(keycloakId);
        d.setSpecializations(new java.util.HashSet<>(specializations));
        return em.persist(d);
    }

    private Patient patient(String first, String last, String egn, String keycloakId, Doctor gp) {
        Patient p = new Patient();
        p.setFirstName(first);
        p.setLastName(last);
        p.setEgn(egn);
        p.setKeycloakId(keycloakId);
        p.setGp(gp);
        return em.persist(p);
    }

    private Visit visit(Patient patient, Doctor doctor, LocalDate date, LocalTime time) {
        Visit v = new Visit();
        v.setPatient(patient);
        v.setDoctor(doctor);
        v.setVisitDate(date);
        v.setVisitTime(time);
        return v;
    }

    private void addDiagnosis(Visit visit, String name) {
        Diagnosis d = new Diagnosis();
        d.setName(name);
        d.setVisit(visit);
        visit.getDiagnoses().add(d);
    }

    private void addPrescription(Visit visit, String note) {
        Prescription p = new Prescription();
        p.setNote(note);
        p.setVisit(visit);
        visit.getPrescriptions().add(p);
    }

    private void addSickLeave(Visit visit, LocalDate validFrom, int days) {
        SickLeave s = new SickLeave();
        s.setValidFrom(validFrom);
        s.setValidForDays(days);
        s.setVisit(visit);
        visit.setSickLeave(s);
    }

    private void insurance(Patient patient, LocalDate coveredMonth, LocalDate paidOn) {
        PaidInsurance i = new PaidInsurance();
        i.setPatient(patient);
        i.setInsuranceDate(coveredMonth);
        i.setPaidOn(paidOn);
        em.persist(i);
    }

    private void audit(String actor, String action, String entityType, Long entityId,
                       LocalDateTime createdAt) {
        AuditLog log = new AuditLog();
        log.setActor(actor);
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setCreatedAt(createdAt);
        em.persist(log);
    }
}
