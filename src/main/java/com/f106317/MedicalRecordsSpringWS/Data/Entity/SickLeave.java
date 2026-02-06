package com.f106317.MedicalRecordsSpringWS.Data.Entity;

import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import lombok.Getter;
import lombok.Setter;

import java.sql.Date;

@Entity
@Getter
@Setter
public class SickLeave extends BaseEntity {

    @OneToOne
    @JoinColumn(name = "visit_id")
    Visit visit;

    Date validFrom;

    int validForDays;

}
