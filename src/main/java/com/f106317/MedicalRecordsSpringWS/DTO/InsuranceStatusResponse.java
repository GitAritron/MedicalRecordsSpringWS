package com.f106317.MedicalRecordsSpringWS.DTO;

import java.util.List;

/**
 * The answer to "has this patient paid their health insurance over the last six months?"
 * <p>
 * WHY this replaced a bare boolean: the specification asks for a <em>справка</em> — something a
 * person reads and acts on — and `false` alone does not say what to do about it. Naming the months
 * still owed does.
 * <p>
 * WHY no month count: the rule works on an unbroken run, and a run understates a patient who paid
 * several months either side of a gap — four of six months paid with a hole in the middle scores
 * one. That number needs explaining wherever it appears, so it is not reported; the verdict decides
 * the question and the months say what is outstanding.
 *
 * @param insured      whether the contributions run unbroken across the window
 * @param unpaidMonths the months in that window with no contribution, oldest first
 */
public record InsuranceStatusResponse(boolean insured, List<ReportDTO.UnpaidMonth> unpaidMonths) {}
