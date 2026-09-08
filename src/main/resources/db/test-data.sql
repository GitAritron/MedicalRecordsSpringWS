-- Seed data for the Medical Records System.
--
-- Shaped so every required report returns something worth looking at: four general
-- practitioners with unevenly sized rosters, diagnoses with a clear frequency ranking, visits
-- spread across roughly eight months, and sick leaves skewed towards particular months and
-- doctors rather than spread flat.
--
-- All dates are relative to CURDATE(). Hardcoded ones silently rot: a patient seeded as fully
-- insured stops being so the moment the calendar rolls past the window, and 'visits in a given
-- period' quietly empties. Relative dates keep the demonstration true whenever it is run.
--
-- Doctors 6-8 and patients 16-24 have no keycloak_id deliberately. They are records-only
-- entries -- people the practice tracks who hold no portal account -- which is a supported
-- state worth having present. The realm ships only 5 doctor and 15 patient logins in any case.
USE medical_records_db;

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

-- 1. Doctors -- general practitioners are 1, 3, 5 and 6; the rest are specialists
INSERT INTO doctor (id, keycloak_id, first_name, last_name, is_gp) VALUES
(1, '22222222-2222-2222-2222-222222222222', 'Georgi', 'Ivanov', TRUE),
(2, 'e2222222-2222-2222-2222-222222222222', 'Dimitar', 'Petrov', FALSE),
(3, 'e3333333-3333-3333-3333-333333333333', 'Elena', 'Koleva', TRUE),
(4, 'e4444444-4444-4444-4444-444444444444', 'Stefan', 'Georgiev', FALSE),
(5, 'e5555555-5555-5555-5555-555555555555', 'Maria', 'Stoyanova', TRUE),
(6, NULL, 'Nikolay', 'Dimitrov', TRUE),
(7, NULL, 'Rumyana', 'Vasileva', FALSE),
(8, NULL, 'Plamen', 'Kirov', FALSE);

-- 2. Specializations
INSERT INTO specialization (id, name) VALUES
(1, 'General Medicine'),
(2, 'Cardiology'),
(3, 'Pediatrics'),
(4, 'Pulmonology'),
(5, 'Endocrinology'),
(6, 'Neurology'),
(7, 'Dermatology'),
(8, 'Orthopaedics');

-- 3. Doctor specializations
INSERT INTO doctor_specialization (doctor_id, specialization_id) VALUES
(1, 1),
(2, 2),
(3, 1),
(3, 3),
(4, 4),
(5, 1),
(6, 1),
(6, 5),
(7, 5),
(8, 6);

-- 4. Patients. Rosters are deliberately uneven so the count-per-GP report has a real ranking:
--    GP 1 has 9, GP 3 has 6, GP 5 has 5, GP 6 has 3. Patient 24 has no GP yet, so the
--    'not assigned' state is visible in the interface too.
INSERT INTO patient (id, keycloak_id, egn, first_name, last_name, gp_doctor_id) VALUES
(1, '33333333-3333-3333-3333-333333333333', '8001156240', 'Ivan', 'Dimitrov', 1),
(2, 'c0222222-3333-3333-3333-333333333333', '7504221485', 'Petar', 'Ivanov', 1),
(3, 'c0333333-3333-3333-3333-333333333333', '9208103321', 'Georgi', 'Stoyanov', 1),
(4, 'c0444444-3333-3333-3333-333333333333', '8811054412', 'Maria', 'Vasileva', 1),
(5, 'c0555555-3333-3333-3333-333333333333', '9503145563', 'Elena', 'Nikolova', 1),
(6, 'c0666666-3333-3333-3333-333333333333', '8307192231', 'Stefan', 'Hristov', 1),
(7, 'c0777777-3333-3333-3333-333333333333', '9102287742', 'Nikolay', 'Popov', 1),
(8, 'c0888888-3333-3333-3333-333333333333', '7809121153', 'Aleksandar', 'Todorov', 1),
(9, 'c0999999-3333-3333-3333-333333333333', '8606048864', 'Yordan', 'Angelov', 1),
(10, 'c1010101-3333-3333-3333-333333333333', '9410309975', 'Borislav', 'Krastev', 3),
(11, 'c1111111-3333-3333-3333-333333333333', '8912250086', 'Tsvetan', 'Iliev', 3),
(12, 'c1212121-3333-3333-3333-333333333333', '9705182297', 'Desislava', 'Petkova', 3),
(13, 'c1313131-3333-3333-3333-333333333333', '9001014408', 'Viktoriya', 'Metodieva', 3),
(14, 'c1414141-3333-3333-3333-333333333333', '8404155519', 'Radoslav', 'Marinov', 3),
(15, 'c1515151-3333-3333-3333-333333333333', '9607076620', 'Teodora', 'Simeonova', 3),
(16, NULL, '8202284431', 'Kaloyan', 'Dobrev', 5),
(17, NULL, '9309177782', 'Silviya', 'Yaneva', 5),
(18, NULL, '7911063354', 'Emil', 'Kostov', 5),
(19, NULL, '9812219905', 'Gergana', 'Aleksieva', 5),
(20, NULL, '8705152276', 'Martin', 'Zlatev', 5),
(21, NULL, '9403088817', 'Rositsa', 'Grozdanova', 6),
(22, NULL, '8108304428', 'Boyan', 'Tsvetkov', 6),
(23, NULL, '9910111239', 'Nadezhda', 'Bozhilova', 6),
(24, NULL, '8609275540', 'Lyubomir', 'Ganchev', NULL);

-- 5. Paid insurance. The check counts distinct months inside the six ending with the current
--    one, so full cover is five months back through today. Patients 5 and 6 sit exactly one
--    month short -- the boundary the rule turns on, and the most useful case to demonstrate.
INSERT INTO paid_insurance (patient_id, insurance_date, paid_on) VALUES
(1, DATE_FORMAT(CURDATE() - INTERVAL 5 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 5 MONTH),
(1, DATE_FORMAT(CURDATE() - INTERVAL 4 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 4 MONTH),
(1, DATE_FORMAT(CURDATE() - INTERVAL 3 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 3 MONTH),
(1, DATE_FORMAT(CURDATE() - INTERVAL 2 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 2 MONTH),
(1, DATE_FORMAT(CURDATE() - INTERVAL 1 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 1 MONTH),
(1, DATE_FORMAT(CURDATE(), '%Y-%m-01'), CURDATE()),
(2, DATE_FORMAT(CURDATE() - INTERVAL 5 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 5 MONTH),
(2, DATE_FORMAT(CURDATE() - INTERVAL 4 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 4 MONTH),
(2, DATE_FORMAT(CURDATE() - INTERVAL 3 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 3 MONTH),
(2, DATE_FORMAT(CURDATE() - INTERVAL 2 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 2 MONTH),
(2, DATE_FORMAT(CURDATE() - INTERVAL 1 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 1 MONTH),
(2, DATE_FORMAT(CURDATE(), '%Y-%m-01'), CURDATE()),
(3, DATE_FORMAT(CURDATE() - INTERVAL 5 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 5 MONTH),
(3, DATE_FORMAT(CURDATE() - INTERVAL 4 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 4 MONTH),
(3, DATE_FORMAT(CURDATE() - INTERVAL 3 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 3 MONTH),
(3, DATE_FORMAT(CURDATE() - INTERVAL 2 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 2 MONTH),
(3, DATE_FORMAT(CURDATE() - INTERVAL 1 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 1 MONTH),
(3, DATE_FORMAT(CURDATE(), '%Y-%m-01'), CURDATE()),
(9, DATE_FORMAT(CURDATE() - INTERVAL 5 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 5 MONTH),
(9, DATE_FORMAT(CURDATE() - INTERVAL 4 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 4 MONTH),
(9, DATE_FORMAT(CURDATE() - INTERVAL 3 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 3 MONTH),
(9, DATE_FORMAT(CURDATE() - INTERVAL 2 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 2 MONTH),
(9, DATE_FORMAT(CURDATE() - INTERVAL 1 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 1 MONTH),
(9, DATE_FORMAT(CURDATE(), '%Y-%m-01'), CURDATE()),
(16, DATE_FORMAT(CURDATE() - INTERVAL 5 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 5 MONTH),
(16, DATE_FORMAT(CURDATE() - INTERVAL 4 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 4 MONTH),
(16, DATE_FORMAT(CURDATE() - INTERVAL 3 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 3 MONTH),
(16, DATE_FORMAT(CURDATE() - INTERVAL 2 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 2 MONTH),
(16, DATE_FORMAT(CURDATE() - INTERVAL 1 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 1 MONTH),
(16, DATE_FORMAT(CURDATE(), '%Y-%m-01'), CURDATE()),
(5, DATE_FORMAT(CURDATE() - INTERVAL 5 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 5 MONTH),
(5, DATE_FORMAT(CURDATE() - INTERVAL 4 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 4 MONTH),
(5, DATE_FORMAT(CURDATE() - INTERVAL 3 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 3 MONTH),
(5, DATE_FORMAT(CURDATE() - INTERVAL 2 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 2 MONTH),
(5, DATE_FORMAT(CURDATE() - INTERVAL 1 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 1 MONTH),
(6, DATE_FORMAT(CURDATE() - INTERVAL 5 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 5 MONTH),
(6, DATE_FORMAT(CURDATE() - INTERVAL 4 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 4 MONTH),
(6, DATE_FORMAT(CURDATE() - INTERVAL 3 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 3 MONTH),
(6, DATE_FORMAT(CURDATE() - INTERVAL 2 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 2 MONTH),
(6, DATE_FORMAT(CURDATE() - INTERVAL 1 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 1 MONTH),
(4, DATE_FORMAT(CURDATE() - INTERVAL 2 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 2 MONTH),
(4, DATE_FORMAT(CURDATE() - INTERVAL 1 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 1 MONTH),
(4, DATE_FORMAT(CURDATE(), '%Y-%m-01'), CURDATE()),
(10, DATE_FORMAT(CURDATE() - INTERVAL 2 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 2 MONTH),
(10, DATE_FORMAT(CURDATE() - INTERVAL 1 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 1 MONTH),
(10, DATE_FORMAT(CURDATE(), '%Y-%m-01'), CURDATE()),
(17, DATE_FORMAT(CURDATE() - INTERVAL 2 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 2 MONTH),
(17, DATE_FORMAT(CURDATE() - INTERVAL 1 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 1 MONTH),
(17, DATE_FORMAT(CURDATE(), '%Y-%m-01'), CURDATE()),
(7, DATE_FORMAT(CURDATE() - INTERVAL 1 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 1 MONTH),
(8, DATE_FORMAT(CURDATE() - INTERVAL 1 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 1 MONTH),
(11, DATE_FORMAT(CURDATE() - INTERVAL 1 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 1 MONTH),
(18, DATE_FORMAT(CURDATE() - INTERVAL 1 MONTH, '%Y-%m-01'), CURDATE() - INTERVAL 1 MONTH);

-- 6. Visits across roughly eight months, so the period reports and the monthly sick-leave
--    ranking both have range to work with.
INSERT INTO visit (id, patient_id, doctor_id, visit_date, visit_time) VALUES
(1, 8, 1, DATE_SUB(CURDATE(), INTERVAL 232 DAY), '09:00:00'),
(2, 1, 7, DATE_SUB(CURDATE(), INTERVAL 228 DAY), '09:30:00'),
(3, 7, 1, DATE_SUB(CURDATE(), INTERVAL 224 DAY), '10:00:00'),
(4, 3, 7, DATE_SUB(CURDATE(), INTERVAL 219 DAY), '10:30:00'),
(5, 15, 6, DATE_SUB(CURDATE(), INTERVAL 214 DAY), '11:00:00'),
(6, 6, 2, DATE_SUB(CURDATE(), INTERVAL 211 DAY), '11:30:00'),
(7, 2, 8, DATE_SUB(CURDATE(), INTERVAL 205 DAY), '13:00:00'),
(8, 16, 5, DATE_SUB(CURDATE(), INTERVAL 200 DAY), '13:30:00'),
(9, 2, 7, DATE_SUB(CURDATE(), INTERVAL 193 DAY), '14:00:00'),
(10, 5, 3, DATE_SUB(CURDATE(), INTERVAL 187 DAY), '14:30:00'),
(11, 17, 5, DATE_SUB(CURDATE(), INTERVAL 182 DAY), '15:00:00'),
(12, 3, 4, DATE_SUB(CURDATE(), INTERVAL 175 DAY), '08:30:00'),
(13, 5, 1, DATE_SUB(CURDATE(), INTERVAL 169 DAY), '09:00:00'),
(14, 18, 5, DATE_SUB(CURDATE(), INTERVAL 164 DAY), '09:30:00'),
(15, 23, 6, DATE_SUB(CURDATE(), INTERVAL 157 DAY), '10:00:00'),
(16, 7, 1, DATE_SUB(CURDATE(), INTERVAL 150 DAY), '10:30:00'),
(17, 4, 1, DATE_SUB(CURDATE(), INTERVAL 143 DAY), '11:00:00'),
(18, 7, 5, DATE_SUB(CURDATE(), INTERVAL 136 DAY), '11:30:00'),
(19, 3, 2, DATE_SUB(CURDATE(), INTERVAL 129 DAY), '13:00:00'),
(20, 21, 5, DATE_SUB(CURDATE(), INTERVAL 122 DAY), '13:30:00'),
(21, 1, 8, DATE_SUB(CURDATE(), INTERVAL 116 DAY), '14:00:00'),
(22, 6, 1, DATE_SUB(CURDATE(), INTERVAL 112 DAY), '14:30:00'),
(23, 14, 3, DATE_SUB(CURDATE(), INTERVAL 108 DAY), '15:00:00'),
(24, 9, 1, DATE_SUB(CURDATE(), INTERVAL 103 DAY), '08:30:00'),
(25, 4, 2, DATE_SUB(CURDATE(), INTERVAL 100 DAY), '09:00:00'),
(26, 5, 4, DATE_SUB(CURDATE(), INTERVAL 94 DAY), '09:30:00'),
(27, 22, 6, DATE_SUB(CURDATE(), INTERVAL 90 DAY), '10:00:00'),
(28, 20, 5, DATE_SUB(CURDATE(), INTERVAL 87 DAY), '10:30:00'),
(29, 2, 1, DATE_SUB(CURDATE(), INTERVAL 81 DAY), '11:00:00'),
(30, 3, 1, DATE_SUB(CURDATE(), INTERVAL 77 DAY), '11:30:00'),
(31, 4, 4, DATE_SUB(CURDATE(), INTERVAL 74 DAY), '13:00:00'),
(32, 12, 3, DATE_SUB(CURDATE(), INTERVAL 69 DAY), '13:30:00'),
(33, 2, 4, DATE_SUB(CURDATE(), INTERVAL 62 DAY), '14:00:00'),
(34, 21, 6, DATE_SUB(CURDATE(), INTERVAL 56 DAY), '14:30:00'),
(35, 13, 3, DATE_SUB(CURDATE(), INTERVAL 51 DAY), '15:00:00'),
(36, 11, 3, DATE_SUB(CURDATE(), INTERVAL 45 DAY), '08:30:00'),
(37, 2, 2, DATE_SUB(CURDATE(), INTERVAL 42 DAY), '09:00:00'),
(38, 1, 2, DATE_SUB(CURDATE(), INTERVAL 35 DAY), '09:30:00'),
(39, 12, 3, DATE_SUB(CURDATE(), INTERVAL 28 DAY), '10:00:00'),
(40, 1, 4, DATE_SUB(CURDATE(), INTERVAL 23 DAY), '10:30:00'),
(41, 8, 1, DATE_SUB(CURDATE(), INTERVAL 19 DAY), '11:00:00'),
(42, 5, 2, DATE_SUB(CURDATE(), INTERVAL 13 DAY), '11:30:00'),
(43, 10, 3, DATE_SUB(CURDATE(), INTERVAL 6 DAY), '13:00:00'),
(44, 1, 1, DATE_SUB(CURDATE(), INTERVAL 2 DAY), '13:30:00'),
(45, 15, 3, DATE_SUB(CURDATE(), INTERVAL 2 DAY), '14:00:00'),
(46, 19, 5, DATE_SUB(CURDATE(), INTERVAL 2 DAY), '14:30:00');

-- 7. Diagnoses. Frequencies are chosen to give the 'most frequently diagnosed' report a clear
--    head and tail rather than a flat list where every condition ties.
INSERT INTO diagnosis (visit_id, name) VALUES
(1, 'Acute Bronchitis'),
(2, 'Acute Bronchitis'),
(3, 'Acute Bronchitis'),
(4, 'Acute Bronchitis'),
(5, 'Acute Bronchitis'),
(6, 'Acute Bronchitis'),
(7, 'Acute Bronchitis'),
(8, 'Hypertension'),
(9, 'Hypertension'),
(10, 'Hypertension'),
(11, 'Hypertension'),
(12, 'Hypertension'),
(13, 'Hypertension'),
(14, 'Influenza Type A'),
(15, 'Influenza Type A'),
(16, 'Influenza Type A'),
(17, 'Influenza Type A'),
(18, 'Influenza Type A'),
(19, 'Type 2 Diabetes'),
(20, 'Type 2 Diabetes'),
(21, 'Type 2 Diabetes'),
(22, 'Type 2 Diabetes'),
(23, 'Migraine'),
(24, 'Migraine'),
(25, 'Migraine'),
(26, 'Migraine'),
(27, 'Bronchial Asthma'),
(28, 'Bronchial Asthma'),
(29, 'Bronchial Asthma'),
(30, 'Allergic Rhinitis'),
(31, 'Allergic Rhinitis'),
(32, 'Allergic Rhinitis'),
(33, 'Pneumonia'),
(34, 'Pneumonia'),
(35, 'Pneumonia'),
(36, 'Gastritis'),
(37, 'Gastritis'),
(38, 'Arrhythmia'),
(39, 'Arrhythmia'),
(40, 'Lumbar Sprain'),
(41, 'Lumbar Sprain'),
(42, 'Routine Checkup'),
(43, 'Routine Checkup'),
(44, 'Iron Deficiency Anaemia'),
(45, 'Atopic Dermatitis'),
(46, 'Tension Headache');

-- 8. Prescriptions -- the treatment tracked alongside each diagnosis
INSERT INTO prescription (visit_id, note) VALUES
(1, 'Rest and hydration, Paracetamol 500mg'),
(2, 'Rest and hydration, Paracetamol 500mg'),
(3, 'Rest and hydration, Paracetamol 500mg'),
(4, 'Rest and hydration, Paracetamol 500mg'),
(5, 'Rest and hydration, Paracetamol 500mg'),
(6, 'Rest and hydration, Paracetamol 500mg'),
(7, 'Rest and hydration, Paracetamol 500mg'),
(8, 'Lisinopril 10mg daily'),
(9, 'Lisinopril 10mg daily'),
(10, 'Lisinopril 10mg daily'),
(11, 'Lisinopril 10mg daily'),
(12, 'Lisinopril 10mg daily'),
(13, 'Lisinopril 10mg daily'),
(14, 'Oseltamivir 75mg twice daily'),
(15, 'Oseltamivir 75mg twice daily'),
(16, 'Oseltamivir 75mg twice daily'),
(17, 'Oseltamivir 75mg twice daily'),
(18, 'Oseltamivir 75mg twice daily'),
(19, 'Metformin 500mg daily'),
(20, 'Metformin 500mg daily'),
(21, 'Metformin 500mg daily'),
(22, 'Metformin 500mg daily'),
(23, 'Sumatriptan 50mg as needed'),
(24, 'Sumatriptan 50mg as needed'),
(25, 'Sumatriptan 50mg as needed'),
(26, 'Sumatriptan 50mg as needed'),
(27, 'Albuterol inhaler as needed'),
(28, 'Albuterol inhaler as needed'),
(29, 'Albuterol inhaler as needed'),
(30, 'Cetirizine 10mg daily'),
(31, 'Cetirizine 10mg daily'),
(32, 'Cetirizine 10mg daily'),
(33, 'Amoxicillin 1000mg twice daily'),
(34, 'Amoxicillin 1000mg twice daily'),
(35, 'Amoxicillin 1000mg twice daily'),
(36, 'Omeprazole 20mg before breakfast'),
(37, 'Omeprazole 20mg before breakfast'),
(38, 'Metoprolol 25mg twice daily'),
(39, 'Metoprolol 25mg twice daily'),
(40, 'Ibuprofen 400mg, physiotherapy referral'),
(41, 'Ibuprofen 400mg, physiotherapy referral'),
(42, 'No medication; review in 12 months'),
(43, 'No medication; review in 12 months'),
(44, 'Ferrous sulfate 200mg daily'),
(45, 'Emollient cream twice daily'),
(46, 'Paracetamol 500mg as needed');

-- 9. Sick leaves. At most one per visit, since sick_leave.visit_id is UNIQUE.
INSERT INTO sick_leave (visit_id, valid_from, valid_for_days) VALUES
(1, DATE_SUB(CURDATE(), INTERVAL 232 DAY), 3),
(3, DATE_SUB(CURDATE(), INTERVAL 224 DAY), 7),
(6, DATE_SUB(CURDATE(), INTERVAL 211 DAY), 10),
(8, DATE_SUB(CURDATE(), INTERVAL 200 DAY), 14),
(10, DATE_SUB(CURDATE(), INTERVAL 187 DAY), 7),
(11, DATE_SUB(CURDATE(), INTERVAL 182 DAY), 3),
(13, DATE_SUB(CURDATE(), INTERVAL 169 DAY), 14),
(14, DATE_SUB(CURDATE(), INTERVAL 164 DAY), 14),
(16, DATE_SUB(CURDATE(), INTERVAL 150 DAY), 14),
(17, DATE_SUB(CURDATE(), INTERVAL 143 DAY), 3),
(18, DATE_SUB(CURDATE(), INTERVAL 136 DAY), 14),
(19, DATE_SUB(CURDATE(), INTERVAL 129 DAY), 5),
(20, DATE_SUB(CURDATE(), INTERVAL 122 DAY), 7),
(22, DATE_SUB(CURDATE(), INTERVAL 112 DAY), 3),
(23, DATE_SUB(CURDATE(), INTERVAL 108 DAY), 14),
(24, DATE_SUB(CURDATE(), INTERVAL 103 DAY), 3),
(25, DATE_SUB(CURDATE(), INTERVAL 100 DAY), 3),
(28, DATE_SUB(CURDATE(), INTERVAL 87 DAY), 5),
(29, DATE_SUB(CURDATE(), INTERVAL 81 DAY), 10),
(30, DATE_SUB(CURDATE(), INTERVAL 77 DAY), 14);
