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

// insuranceDate = month being covered, paidOn = when money arrived. Coverage check uses only insuranceDate.
@Entity
@Table(name = "paid_insurance",
        // One payment per patient per month. Unique constraint also serves as the index.
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
