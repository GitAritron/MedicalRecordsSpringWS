-- Bulgarian Data (Latin Script) for Medical Records System
USE medical_records_db;

-- Clear existing data
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE sick_leave;
TRUNCATE TABLE audit_log;
TRUNCATE TABLE prescription;
TRUNCATE TABLE diagnosis;
TRUNCATE TABLE visit;
TRUNCATE TABLE paid_insurance;
TRUNCATE TABLE doctor_specialization;
TRUNCATE TABLE specialization;
TRUNCATE TABLE patient;
TRUNCATE TABLE doctor;
SET FOREIGN_KEY_CHECKS = 1;

-- 1. Doctors (5 Doctors)
INSERT INTO doctor (id, keycloak_id, first_name, last_name, is_gp) VALUES 
(1, '22222222-2222-2222-2222-222222222222', 'Georgi', 'Ivanov', TRUE),
(2, 'e2222222-2222-2222-2222-222222222222', 'Dimitar', 'Petrov', FALSE),
(3, 'e3333333-3333-3333-3333-333333333333', 'Elena', 'Koleva', TRUE),
(4, 'e4444444-4444-4444-4444-444444444444', 'Stefan', 'Georgiev', FALSE),
(5, 'e5555555-5555-5555-5555-555555555555', 'Maria', 'Stoyanova', TRUE);

-- 2. Specializations
INSERT INTO specialization (id, name) VALUES 
(1, 'General Medicine'),
(2, 'Oncology'),
(3, 'Endocrinology'),
(4, 'Cardiology'),
(5, 'Pediatrics');

-- 3. Doctor Specializations
INSERT INTO doctor_specialization (doctor_id, specialization_id) VALUES 
(1, 1),
(2, 2),
(3, 3),
(4, 4),
(5, 5);

-- 4. Patients (15 Patients with valid 10-digit Bulgarian EGNs)
INSERT INTO patient (id, keycloak_id, egn, first_name, last_name, gp_doctor_id) VALUES 
(1, '33333333-3333-3333-3333-333333333333', '8001156240', 'Ivan', 'Dimitrov', 1),
(2, 'c0222222-3333-3333-3333-333333333333', '7504221485', 'Petar', 'Ivanov', 1),
(3, 'c0333333-3333-3333-3333-333333333333', '9208103321', 'Georgi', 'Stoyanov', 1),
(4, 'c0444444-3333-3333-3333-333333333333', '8811054412', 'Maria', 'Vasileva', 3),
(5, 'c0555555-3333-3333-3333-333333333333', '9503145563', 'Elena', 'Nikolova', 3),
(6, 'c0666666-3333-3333-3333-333333333333', '8307192231', 'Stefan', 'Hristov', 5),
(7, 'c0777777-3333-3333-3333-333333333333', '9102287742', 'Nikolay', 'Popov', 5),
(8, 'c0888888-3333-3333-3333-333333333333', '7809121153', 'Aleksandar', 'Todorov', 1),
(9, 'c0999999-3333-3333-3333-333333333333', '8606048864', 'Yordan', 'Angelov', 3),
(10, 'c1010101-3333-3333-3333-333333333333', '9410309975', 'Borislav', 'Krastev', 5),
(11, 'c1111111-3333-3333-3333-333333333333', '8912250086', 'Tsvetan', 'Iliev', 1),
(12, 'c1212121-3333-3333-3333-333333333333', '9705182297', 'Desislava', 'Petkova', 3),
(13, 'c1313131-3333-3333-3333-333333333333', '9001014408', 'Viktoriya', 'Metodieva', 5),
(14, 'c1414141-3333-3333-3333-333333333333', '8404155519', 'Radoslav', 'Marinov', 1),
(15, 'c1515151-3333-3333-3333-333333333333', '9607076620', 'Teodora', 'Simeonova', 3);

-- 5. Paid Insurance (Patient 1 and 2 have full 6-month continuous coverage; others have partial/none)
-- Dates are relative to CURDATE() rather than hardcoded. The coverage check looks at the 6 calendar
-- months ending with the current one, so fixed months silently expire: a patient seeded as fully
-- covered would start reporting as uninsured the moment the calendar rolled past the seeded window.
-- Plain CURDATE() is the current month; INTERVAL 5 MONTH is the oldest month still in the window.
INSERT INTO paid_insurance (patient_id, insurance_date, paid_on) VALUES
-- Patient 1: the 6 consecutive months ending with the current one
(1, DATE_FORMAT(CURDATE() - INTERVAL 5 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 5 MONTH),
(1, DATE_FORMAT(CURDATE() - INTERVAL 4 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 4 MONTH),
(1, DATE_FORMAT(CURDATE() - INTERVAL 3 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 3 MONTH),
(1, DATE_FORMAT(CURDATE() - INTERVAL 2 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 2 MONTH),
(1, DATE_FORMAT(CURDATE() - INTERVAL 1 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 1 MONTH),
(1, DATE_FORMAT(CURDATE(),                     '%Y-%m-01'), CURDATE()),
-- Patient 2: the same 6 consecutive months
(2, DATE_FORMAT(CURDATE() - INTERVAL 5 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 5 MONTH),
(2, DATE_FORMAT(CURDATE() - INTERVAL 4 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 4 MONTH),
(2, DATE_FORMAT(CURDATE() - INTERVAL 3 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 3 MONTH),
(2, DATE_FORMAT(CURDATE() - INTERVAL 2 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 2 MONTH),
(2, DATE_FORMAT(CURDATE() - INTERVAL 1 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 1 MONTH),
(2, DATE_FORMAT(CURDATE(),                     '%Y-%m-01'), CURDATE()),
-- Other patients: partial coverage (a single month each, so never 6 of 6)
(3, DATE_FORMAT(CURDATE() - INTERVAL 3 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 3 MONTH),
(4, DATE_FORMAT(CURDATE() - INTERVAL 2 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 2 MONTH),
(5, DATE_FORMAT(CURDATE() - INTERVAL 1 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 1 MONTH),
(6, DATE_FORMAT(CURDATE() - INTERVAL 4 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 4 MONTH),
(7, DATE_FORMAT(CURDATE() - INTERVAL 2 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 2 MONTH),
(8, DATE_FORMAT(CURDATE() - INTERVAL 1 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 1 MONTH);

-- 6. Visits
INSERT INTO visit (id, patient_id, doctor_id, visit_date, visit_time) VALUES 
(1, 1, 1, '2026-07-01', '09:00:00'),
(2, 2, 1, '2026-07-02', '10:00:00'),
(3, 3, 1, '2026-07-03', '11:00:00'),
(4, 4, 3, '2026-07-05', '14:00:00'),
(5, 5, 3, '2026-07-06', '15:00:00'),
(6, 6, 5, '2026-07-07', '09:30:00'),
(7, 7, 5, '2026-07-08', '10:30:00'),
(8, 8, 2, '2026-07-09', '11:30:00'),
(9, 9, 4, '2026-07-10', '13:00:00'),
(10, 10, 5, '2026-07-11', '14:30:00');

-- 7. Diagnoses
INSERT INTO diagnosis (visit_id, name) VALUES 
(1, 'Acute Bronchitis'),
(2, 'Influenza Type A'),
(3, 'Migraine'),
(4, 'Type 2 Diabetes'),
(5, 'Hypertension'),
(6, 'Pneumonia'),
(7, 'Allergic Rhinitis'),
(8, 'Routine Checkup'),
(9, 'Arrhythmia'),
(10, 'Bronchial Asthma');

-- 8. Prescriptions
INSERT INTO prescription (visit_id, note) VALUES 
(1, 'Rest and hydration, Paracetamol 500mg'),
(2, 'Oseltamivir 75mg twice daily'),
(3, 'Sumatriptan 50mg as needed'),
(4, 'Metformin 500mg daily'),
(5, 'Lisinopril 10mg daily'),
(6, 'Amoxicillin 1000mg twice daily'),
(7, 'Cetirizine 10mg daily'),
(8, 'Follow-up PET scan in 3 months'),
(9, 'Metoprolol 25mg twice daily'),
(10, 'Albuterol inhaler as needed');

-- 9. Sick Leaves
INSERT INTO sick_leave (visit_id, valid_from, valid_for_days) VALUES 
(1, '2026-07-01', 3),
(2, '2026-07-02', 5),
(3, '2026-07-03', 2),
(5, '2026-07-06', 7),
(6, '2026-07-07', 4),
(10, '2026-07-11', 5);
