package com.f106317.MedicalRecordsSpringWS.Data.Entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * One month of paid health insurance for one patient.
 * WHY two dates: {@code insuranceDate} is the month being covered, {@code paidOn} is when the money
 * actually arrived. Paying three months late still covers the correct months, so the continuous-cover
 * check must read the former and never the latter.
 */
@Entity
@Table(name = "paid_insurance",
        // Mirrors init.sql. Hibernate ignores this under ddl-auto: validate, but the test schema is
        // generated from these entities — without it the rule is unenforced and untestable there.
        // No separate index on (patient_id, insurance_date): MySQL backs the unique constraint with
        // one over exactly those columns in that order, so a second would be maintained for nothing.
        uniqueConstraints = @UniqueConstraint(
                name = "uq_patient_insurance_date",
                columnNames = {"patient_id", "insurance_date"}))
@Getter
@Setter
@NoArgsConstructor
public class PaidInsurance extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @Column(name = "insurance_date", nullable = false)
    private LocalDate insuranceDate;

    @Column(name = "paid_on", nullable = false)
    private LocalDate paidOn;
}
