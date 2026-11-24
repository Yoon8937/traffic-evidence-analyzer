package com.backend.analysis;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class AnalysisJobClaimService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisJobClaimService.class);

    private final EntityManager entityManager;
    private final AnalysisJobRepository analysisJobRepository;

    public AnalysisJobClaimService(EntityManager entityManager, AnalysisJobRepository analysisJobRepository) {
        this.entityManager = entityManager;
        this.analysisJobRepository = analysisJobRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ClaimedJob> claimDispatchJobs(
            int batchSize,
            LocalDateTime now,
            int leaseSeconds,
            String owner
    ) {
        LocalDateTime firstDue = now.minusSeconds(AnalysisJobService.FIRST_RETRY_DELAY_SECONDS);
        LocalDateTime secondDue = now.minusSeconds(AnalysisJobService.SECOND_RETRY_DELAY_SECONDS);
        String token = newLeaseToken();
        String sql = """
                WITH picked AS (
                    SELECT id
                    FROM analysis_jobs
                    WHERE status = :pending
                      AND accepted_at IS NULL
                      AND error_code IN (""" + inList(AnalysisJobService.RETRYABLE_ERROR_CODES) + """
                      )
                      AND (
                            dispatch_inflight = TRUE
                            OR (retry_count = 0 AND (updated_at IS NULL OR updated_at <= :firstDue))
                            OR (retry_count = 1 AND (updated_at IS NULL OR updated_at <= :secondDue))
                            OR (retry_count >= :maxResends)
                          )
                      AND (scheduler_lease_until IS NULL OR scheduler_lease_until <= :now)
                    ORDER BY updated_at NULLS FIRST
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE analysis_jobs j
                SET scheduler_lease_until = :until,
                    scheduler_lease_owner = :owner,
                    scheduler_lease_task = :task,
                    scheduler_lease_token = :token
                FROM picked
                WHERE j.id = picked.id
                RETURNING j.id
                """;
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("pending", AnalysisJob.STATUS_PENDING);
        query.setParameter("firstDue", Timestamp.valueOf(firstDue));
        query.setParameter("secondDue", Timestamp.valueOf(secondDue));
        query.setParameter("maxResends", AnalysisJob.MAX_DISPATCH_RESENDS);
        query.setParameter("now", Timestamp.valueOf(now));
        query.setParameter("batchSize", batchSize);
        query.setParameter("until", Timestamp.valueOf(now.plusSeconds(leaseSeconds)));
        query.setParameter("owner", owner);
        query.setParameter("task", AnalysisJob.SCHEDULER_TASK_DISPATCH);
        query.setParameter("token", token);
        return loadClaimed(query, AnalysisJob.SCHEDULER_TASK_DISPATCH, owner, token);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ClaimedJob> claimRecoveryJobs(
            int batchSize,
            LocalDateTime now,
            int leaseSeconds,
            String owner
    ) {
        String token = newLeaseToken();
        String sql = """
                WITH picked AS (
                    SELECT id
                    FROM analysis_jobs
                    WHERE status = :pending
                      AND (
                            error_code IN (""" + inList(AnalysisJobService.STATUS_RECOVERY_ERROR_CODES) + """
                            )
                            OR (accepted_at IS NOT NULL AND error_code IS NULL)
                          )
                      AND (error_code IS NULL OR error_code NOT IN (""" + inList(AnalysisJobService.RETRYABLE_ERROR_CODES) + """
                          ))
                      AND (scheduler_lease_until IS NULL OR scheduler_lease_until <= :now)
                    ORDER BY updated_at NULLS FIRST
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE analysis_jobs j
                SET scheduler_lease_until = :until,
                    scheduler_lease_owner = :owner,
                    scheduler_lease_task = :task,
                    scheduler_lease_token = :token
                FROM picked
                WHERE j.id = picked.id
                RETURNING j.id
                """;
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("pending", AnalysisJob.STATUS_PENDING);
        query.setParameter("now", Timestamp.valueOf(now));
        query.setParameter("batchSize", batchSize);
        query.setParameter("until", Timestamp.valueOf(now.plusSeconds(leaseSeconds)));
        query.setParameter("owner", owner);
        query.setParameter("task", AnalysisJob.SCHEDULER_TASK_RECOVERY);
        query.setParameter("token", token);
        return loadClaimed(query, AnalysisJob.SCHEDULER_TASK_RECOVERY, owner, token);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ClaimedJob> claimTimeoutJobs(
            int batchSize,
            LocalDateTime now,
            LocalDateTime startedBefore,
            int leaseSeconds,
            String owner
    ) {
        String token = newLeaseToken();
        String sql = """
                WITH picked AS (
                    SELECT id
                    FROM analysis_jobs
                    WHERE status = :processing
                      AND started_at IS NOT NULL
                      AND started_at <= :startedBefore
                      AND (scheduler_lease_until IS NULL OR scheduler_lease_until <= :now)
                    ORDER BY started_at
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE analysis_jobs j
                SET scheduler_lease_until = :until,
                    scheduler_lease_owner = :owner,
                    scheduler_lease_task = :task,
                    scheduler_lease_token = :token
                FROM picked
                WHERE j.id = picked.id
                RETURNING j.id
                """;
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("processing", AnalysisJob.STATUS_PROCESSING);
        query.setParameter("startedBefore", Timestamp.valueOf(startedBefore));
        query.setParameter("now", Timestamp.valueOf(now));
        query.setParameter("batchSize", batchSize);
        query.setParameter("until", Timestamp.valueOf(now.plusSeconds(leaseSeconds)));
        query.setParameter("owner", owner);
        query.setParameter("task", AnalysisJob.SCHEDULER_TASK_TIMEOUT);
        query.setParameter("token", token);
        return loadClaimed(query, AnalysisJob.SCHEDULER_TASK_TIMEOUT, owner, token);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseLease(SchedulerLease lease) {
        if (lease == null || lease.getJobId() == null) {
            return;
        }
        entityManager.createNativeQuery("""
                        UPDATE analysis_jobs
                        SET scheduler_lease_until = NULL,
                            scheduler_lease_owner = NULL,
                            scheduler_lease_task = NULL,
                            scheduler_lease_token = NULL
                        WHERE id = :id
                          AND scheduler_lease_owner = :owner
                          AND scheduler_lease_task = :task
                          AND scheduler_lease_token = :token
                        """)
                .setParameter("id", lease.getJobId())
                .setParameter("owner", lease.getOwner())
                .setParameter("task", lease.getTask())
                .setParameter("token", lease.getToken())
                .executeUpdate();
    }

    @Transactional(readOnly = true)
    public boolean isDispatchInFlight(UUID jobId) {
        if (jobId == null) {
            return false;
        }
        List<?> rows = entityManager.createNativeQuery("""
                        SELECT dispatch_inflight
                        FROM analysis_jobs
                        WHERE id = :id
                        """)
                .setParameter("id", jobId)
                .getResultList();
        if (rows.isEmpty()) {
            return false;
        }
        return asBoolean(rows.get(0));
    }

    @Transactional
    public void setDispatchInFlight(UUID jobId, boolean inFlight) {
        if (jobId == null) {
            return;
        }
        entityManager.createNativeQuery("""
                        UPDATE analysis_jobs
                        SET dispatch_inflight = :inFlight
                        WHERE id = :id
                        """)
                .setParameter("inFlight", inFlight)
                .setParameter("id", jobId)
                .executeUpdate();
    }

    @Transactional
    public boolean reserveDispatchAttempt(SchedulerLease lease, int retryCount) {
        if (lease == null || lease.getJobId() == null) {
            return false;
        }
        int updated = entityManager.createNativeQuery("""
                        UPDATE analysis_jobs
                        SET retry_count = :retryCount,
                            updated_at = :now,
                            dispatch_inflight = TRUE
                        WHERE id = :id
                          AND scheduler_lease_owner = :owner
                          AND scheduler_lease_task = :task
                          AND scheduler_lease_token = :token
                        """)
                .setParameter("retryCount", retryCount)
                .setParameter("now", Timestamp.valueOf(LocalDateTime.now()))
                .setParameter("id", lease.getJobId())
                .setParameter("owner", lease.getOwner())
                .setParameter("task", lease.getTask())
                .setParameter("token", lease.getToken())
                .executeUpdate();
        return updated == 1;
    }

    @Transactional(readOnly = true)
    public boolean holdsLease(SchedulerLease lease) {
        return countLeaseMatches(lease, false) == 1;
    }

    @Transactional
    public boolean applyIfLeaseHeld(SchedulerLease lease, Consumer<AnalysisJob> work) {
        return applyIfLeaseHeld(lease, work, false);
    }

    @Transactional
    public boolean applyIfLeaseHeld(
            SchedulerLease lease,
            Consumer<AnalysisJob> work,
            boolean clearDispatchInFlight
    ) {
        if (lease == null || work == null) {
            return false;
        }
        if (countLeaseMatches(lease, true) != 1) {
            log.warn("job write skipped because scheduler lease is no longer held");
            return false;
        }
        AnalysisJob job = analysisJobRepository.findByIdForUpdate(lease.getJobId()).orElse(null);
        if (job == null) {
            return false;
        }
        work.accept(job);
        if (clearDispatchInFlight) {
            setDispatchInFlightInternal(lease.getJobId(), false);
        }
        return true;
    }

    private int countLeaseMatches(SchedulerLease lease, boolean forUpdate) {
        if (lease == null || lease.getJobId() == null) {
            return 0;
        }
        String sql = """
                SELECT id
                FROM analysis_jobs
                WHERE id = :id
                  AND scheduler_lease_owner = :owner
                  AND scheduler_lease_task = :task
                  AND scheduler_lease_token = :token
                """;
        if (forUpdate) {
            sql = sql + " FOR UPDATE";
        }
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("id", lease.getJobId());
        query.setParameter("owner", lease.getOwner());
        query.setParameter("task", lease.getTask());
        query.setParameter("token", lease.getToken());
        return query.getResultList().size();
    }

    private void setDispatchInFlightInternal(UUID jobId, boolean inFlight) {
        entityManager.createNativeQuery("""
                        UPDATE analysis_jobs
                        SET dispatch_inflight = :inFlight
                        WHERE id = :id
                        """)
                .setParameter("inFlight", inFlight)
                .setParameter("id", jobId)
                .executeUpdate();
    }

    private List<ClaimedJob> loadClaimed(Query query, String task, String owner, String token) {
        List<?> rows = query.getResultList();
        List<UUID> ids = new ArrayList<UUID>();
        for (Object row : rows) {
            ids.add(toUuid(row));
        }
        if (ids.isEmpty()) {
            log.info("job claim found none. scheduler_type={}", task);
            return List.of();
        }
        log.info("job claim succeeded. scheduler_type={}, owner={}, count={}", task, owner, ids.size());
        List<ClaimedJob> claimed = new ArrayList<ClaimedJob>();
        for (AnalysisJob job : analysisJobRepository.findAllById(ids)) {
            claimed.add(new ClaimedJob(job, new SchedulerLease(job.getId(), owner, task, token)));
        }
        return claimed;
    }

    private String newLeaseToken() {
        return UUID.randomUUID().toString();
    }

    private UUID toUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(String.valueOf(value));
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = String.valueOf(value);
        return "t".equalsIgnoreCase(text) || "true".equalsIgnoreCase(text);
    }

    private String inList(Collection<String> codes) {
        return codes.stream()
                .map(code -> "'" + code.replace("'", "''") + "'")
                .collect(Collectors.joining(", "));
    }
}
