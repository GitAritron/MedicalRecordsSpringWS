package com.f106317.MedicalRecordsSpringWS.DTO;

/**
 * The answer to "has this patient paid their health insurance over the last six months?"
 * <p>
 * WHY this replaced a bare boolean: the specification asks for a <em>справка</em> — something a
 * person reads and acts on — and `false` alone does not distinguish a patient who missed a single
 * month from one who has never paid at all. Returning the count alongside the verdict lets both the
 * patient's own dashboard and the practice-wide report say how far short the coverage falls.
 *
 * @param insured        whether every month in the window is covered
 * @param coveredMonths  distinct months paid inside the window
 * @param requiredMonths the window length the rule checks — 6
 */
public record InsuranceStatusResponse(boolean insured, long coveredMonths, int requiredMonths) {}
