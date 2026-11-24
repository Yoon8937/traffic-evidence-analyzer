package com.backend.analysis;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisJobClaimServiceTest {

    @Mock
    private AnalysisJobRepository analysisJobRepository;
    @Mock
    private EntityManager entityManager;
    @Mock
    private Query query;

    private AnalysisJobClaimService claimService;

    @BeforeEach
    void setUp() {
        claimService = new AnalysisJobClaimService(entityManager, analysisJobRepository);
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        lenient().when(query.setParameter(anyString(), any())).thenReturn(query);
    }

    @Test
    void retryableAndRecoveryErrorCodesAreDisjoint() {
        for (String code : AnalysisJobService.RETRYABLE_ERROR_CODES) {
            assertTrue(!AnalysisJobService.STATUS_RECOVERY_ERROR_CODES.contains(code));
        }
        assertTrue(AnalysisJobService.STATUS_RECOVERY_ERROR_CODES.contains(AnalysisJob.ERROR_GET_NOT_FOUND));
        assertTrue(!AnalysisJobService.STATUS_RECOVERY_ERROR_CODES.contains(AnalysisJob.ERROR_GET_NOT_FOUND_EXHAUSTED));
    }

    @Test
    void leaseOwnerDiffersPerSchedulerInstance() {
        AnalysisSchedulerProperties properties = new AnalysisSchedulerProperties();
        AnalysisJobScheduler first = new AnalysisJobScheduler(
                properties,
                claimService,
                null,
                null,
                null
        );
        AnalysisJobScheduler second = new AnalysisJobScheduler(
                properties,
                claimService,
                null,
                null,
                null
        );
        assertTrue(!first.instanceId().equals(second.instanceId()));
        UUID jobId = UUID.randomUUID();
        assertTrue(jobId != null);
        LocalDateTime now = LocalDateTime.now();
        assertTrue(now.minusMinutes(properties.getProcessingTimeoutMinutes()).isBefore(now));
    }

    @Test
    void releaseLeaseRequiresOwnerTaskAndToken() {
        SchedulerLease lease = new SchedulerLease(
                UUID.randomUUID(),
                "owner-a",
                AnalysisJob.SCHEDULER_TASK_DISPATCH,
                "token-a"
        );
        when(query.executeUpdate()).thenReturn(1);

        claimService.releaseLease(lease);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        String releaseSql = sql.getValue();
        assertTrue(releaseSql.contains("scheduler_lease_owner = :owner"));
        assertTrue(releaseSql.contains("scheduler_lease_task = :task"));
        assertTrue(releaseSql.contains("scheduler_lease_token = :token"));
        verify(query).setParameter("owner", "owner-a");
        verify(query).setParameter("task", AnalysisJob.SCHEDULER_TASK_DISPATCH);
        verify(query).setParameter("token", "token-a");
    }

    @Test
    void releaseLeaseDoesNotClearWhenOwnerTaskOrTokenMismatch() {
        SchedulerLease stale = new SchedulerLease(
                UUID.randomUUID(),
                "old-owner",
                AnalysisJob.SCHEDULER_TASK_DISPATCH,
                "old-token"
        );
        when(query.executeUpdate()).thenReturn(0);

        claimService.releaseLease(stale);

        verify(query).executeUpdate();
        verify(analysisJobRepository, never()).save(any());
    }

    @Test
    void applyIfLeaseHeldSkipsWriterWhenTokenNoLongerMatches() {
        SchedulerLease stale = new SchedulerLease(
                UUID.randomUUID(),
                "old-owner",
                AnalysisJob.SCHEDULER_TASK_DISPATCH,
                "old-token"
        );
        when(query.getResultList()).thenReturn(List.of());
        AtomicBoolean ran = new AtomicBoolean(false);

        boolean applied = claimService.applyIfLeaseHeld(stale, job -> ran.set(true), true);

        assertFalse(applied);
        assertFalse(ran.get());
        verify(analysisJobRepository, never()).findByIdForUpdate(any());
        verify(analysisJobRepository, never()).save(any());
    }

    @Test
    void applyIfLeaseHeldRunsWriterWhenOwnerTaskAndTokenMatch() {
        UUID jobId = UUID.randomUUID();
        SchedulerLease lease = new SchedulerLease(
                jobId,
                "owner-b",
                AnalysisJob.SCHEDULER_TASK_TIMEOUT,
                "token-b"
        );
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        job.setId(jobId);
        when(query.getResultList()).thenReturn(List.of(jobId));
        when(analysisJobRepository.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
        when(query.executeUpdate()).thenReturn(1);

        boolean applied = claimService.applyIfLeaseHeld(lease, current -> current.setStatus(AnalysisJob.STATUS_FAILED), true);

        assertTrue(applied);
        assertEquals(AnalysisJob.STATUS_FAILED, job.getStatus());
        verify(analysisJobRepository).findByIdForUpdate(jobId);
    }

    @Test
    void reserveDispatchAttemptRequiresMatchingLease() {
        SchedulerLease lease = new SchedulerLease(
                UUID.randomUUID(),
                "owner-c",
                AnalysisJob.SCHEDULER_TASK_DISPATCH,
                "token-c"
        );
        when(query.executeUpdate()).thenReturn(0);

        assertFalse(claimService.reserveDispatchAttempt(lease, 2));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        assertTrue(sql.getValue().contains("dispatch_inflight = TRUE"));
        assertTrue(sql.getValue().contains("scheduler_lease_token = :token"));
    }

}
