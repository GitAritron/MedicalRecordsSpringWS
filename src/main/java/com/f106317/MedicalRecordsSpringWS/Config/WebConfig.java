package com.f106317.MedicalRecordsSpringWS.Config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;

/**
 * Pins the JSON shape of every paged response.
 * <p>
 * WHY: the audit log endpoint returns a {@code Page}, and serialising Spring's {@code PageImpl}
 * directly has been logged as unsupported since Spring Data 3.3 — its JSON is an implementation
 * detail with no guarantee of staying put across versions. The audit log's Prev / Next controls
 * read exactly those fields, so leaving the shape unpinned would make the paging controls the first
 * thing an upgrade breaks.
 * <p>
 * VIA_DTO wraps the page as <code>{ content, page: { size, number, totalElements, totalPages } }</code>.
 * {@code content} is unchanged, so anything reading only the rows is unaffected.
 */
@Configuration
@EnableSpringDataWebSupport(pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
public class WebConfig {
}
