package com.f106317.MedicalRecordsSpringWS.Data.Entity;

import com.fasterxml.jackson.databind.ser.Serializers;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

import java.sql.Date;


@Entity
@Getter
@Setter
public class PaidInsurance extends BaseEntity {

    @ManyToOne
    @JoinColumn(name = "patient_id")
    Patient patient;

    Date month;

    Date paidOn;
}
