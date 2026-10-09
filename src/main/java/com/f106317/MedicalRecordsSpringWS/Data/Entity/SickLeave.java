package com.f106317.MedicalRecordsSpringWS.Data.Entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Check;

import java.time.LocalDate;

// One sick leave per visit. @OneToOne enforces uniqueness.
@Entity
@Table(name = "sick_leave", indexes = @Index(name = "idx_sl_valid_from", columnList = "valid_from"))
// DB-level check constraint - the API validates via @Min/@Max, this is the backstop.
@Check(name = "chk_valid_for_days", constraints = "valid_for_days > 0 AND valid_for_days <= 365")
@Getter
@Setter
@NoArgsConstructor
public class SickLeave extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_for_days", nullable = false)
    private int validForDays;
}
