package com.f106317.MedicalRecordsSpringWS.Exception;

import java.time.Instant;
import java.util.Map;

public record ApiError(
        String code,
        String message,
        String path,
        Instant timestamp,
        Map<String, String> details
) {}
