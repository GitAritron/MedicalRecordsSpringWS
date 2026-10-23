package com.f106317.MedicalRecordsSpringWS.Service;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.AuditLog;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private AuthorizationService authorizationService;

    @InjectMocks
    private AuditLogService auditLogService;

    @Test
    void log_savesAuditEntry() {
        when(auditLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditLogService.log(null, "CREATE", "Doctor", 1L, "Dr. Georgi Ivanov");

        // Unauthenticated actions are logged as "system", not dropped.
        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(1)).save(saved.capture());
        assertEquals("system", saved.getValue().getActor());
    }

    @Test
    void getAuditLogs_returnsPage() {
        AuditLog log = new AuditLog();
        log.setActor("admin");
        log.setAction("CREATE");
        log.setEntityType("Doctor");
        log.setEntityId(1L);
        Page<AuditLog> page = new PageImpl<>(List.of(log));
        when(auditLogRepository.findAllByOrderByCreatedAtDesc(any(PageRequest.class))).thenReturn(page);

        Page<AuditLog> result = auditLogService.getAuditLogs(0, 10);

        assertEquals(1, result.getContent().size());
        assertEquals("admin", result.getContent().get(0).getActor());
    }

    @Test
    void getAuditLogs_clampsSizeToMax100() {
        Page<AuditLog> page = new PageImpl<>(List.of());
        when(auditLogRepository.findAllByOrderByCreatedAtDesc(any(PageRequest.class))).thenReturn(page);

        auditLogService.getAuditLogs(0, 500);

        verify(auditLogRepository).findAllByOrderByCreatedAtDesc(PageRequest.of(0, 100));
    }
}
