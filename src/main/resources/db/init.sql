CREATE DATABASE IF NOT EXISTS medical_records_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE medical_records_db;

CREATE TABLE IF NOT EXISTS doctor (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    keycloak_id VARCHAR(36) UNIQUE,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    is_gp BOOLEAN NOT NULL DEFAULT FALSE
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS specialization (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    name VARCHAR(100) NOT NULL UNIQUE
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS doctor_specialization (
    doctor_id BIGINT NOT NULL,
    specialization_id BIGINT NOT NULL,
    PRIMARY KEY (doctor_id, specialization_id),
    CONSTRAINT fk_ds_doctor FOREIGN KEY (doctor_id) REFERENCES doctor(id) ON DELETE CASCADE,
    CONSTRAINT fk_ds_specialization FOREIGN KEY (specialization_id) REFERENCES specialization(id) ON DELETE CASCADE
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS patient (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    keycloak_id VARCHAR(36) UNIQUE,
    egn CHAR(10) NOT NULL UNIQUE,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    gp_doctor_id BIGINT,
    CONSTRAINT fk_patient_gp FOREIGN KEY (gp_doctor_id) REFERENCES doctor(id) ON DELETE SET NULL
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS paid_insurance (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    patient_id BIGINT NOT NULL,
    insurance_date DATE NOT NULL,
    paid_on DATE NOT NULL,
    CONSTRAINT fk_pi_patient FOREIGN KEY (patient_id) REFERENCES patient(id) ON DELETE CASCADE,
    CONSTRAINT uq_patient_insurance_date UNIQUE (patient_id, insurance_date)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS visit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    patient_id BIGINT NOT NULL,
    doctor_id BIGINT NOT NULL,
    visit_date DATE NOT NULL,
    visit_time TIME NOT NULL,
    CONSTRAINT fk_visit_patient FOREIGN KEY (patient_id) REFERENCES patient(id) ON DELETE CASCADE,
    CONSTRAINT fk_visit_doctor FOREIGN KEY (doctor_id) REFERENCES doctor(id) ON DELETE CASCADE,
    CONSTRAINT uq_doctor_slot UNIQUE (doctor_id, visit_date, visit_time),
    CONSTRAINT uq_patient_slot UNIQUE (patient_id, visit_date, visit_time)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS diagnosis (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    visit_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    CONSTRAINT fk_diagnosis_visit FOREIGN KEY (visit_id) REFERENCES visit(id) ON DELETE CASCADE
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS prescription (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    visit_id BIGINT NOT NULL,
    note TEXT NOT NULL,
    CONSTRAINT fk_prescription_visit FOREIGN KEY (visit_id) REFERENCES visit(id) ON DELETE CASCADE
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sick_leave (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    visit_id BIGINT NOT NULL UNIQUE,
    valid_from DATE NOT NULL,
    valid_for_days INT NOT NULL,
    CONSTRAINT fk_sl_visit FOREIGN KEY (visit_id) REFERENCES visit(id) ON DELETE CASCADE,
    CONSTRAINT chk_valid_for_days CHECK (valid_for_days > 0 AND valid_for_days <= 365)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    actor VARCHAR(100) NOT NULL,
    action VARCHAR(50) NOT NULL,
    entity_type VARCHAR(50) NOT NULL,
    entity_id BIGINT NOT NULL,
    -- Human-readable description of the record at the time of the action. Nullable because the
    -- label is a convenience, and because it cannot always be derived (e.g. a deleted parent).
    entity_label VARCHAR(150),
    created_at DATETIME NOT NULL
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE INDEX idx_visit_date ON visit(visit_date);
CREATE INDEX idx_diagnosis_name ON diagnosis(name);
CREATE INDEX idx_sl_valid_from ON sick_leave(valid_from);
CREATE INDEX idx_pi_patient_date ON paid_insurance(patient_id, insurance_date);
CREATE INDEX idx_al_entity ON audit_log(entity_type, entity_id);
CREATE INDEX idx_al_actor ON audit_log(actor);
CREATE INDEX idx_al_created ON audit_log(created_at);
