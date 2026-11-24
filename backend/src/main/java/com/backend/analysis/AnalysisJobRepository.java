package com.backend.analysis;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, UUID> {

    @Query("""
            select j from AnalysisJob j
            where j.status = :pending
              and j.acceptedAt is null
              and j.retryCount < :maxResends
              and j.errorCode in :retryableErrorCodes
              and (
                    (j.retryCount = 0 and (j.updatedAt is null or j.updatedAt <= :firstFailureDueAt))
                    or (j.retryCount = 1 and (j.updatedAt is null or j.updatedAt <= :secondFailureDueAt))
                  )
            """)
    List<AnalysisJob> findPendingRetriesDue(
            @Param("pending") String pending,
            @Param("maxResends") int maxResends,
            @Param("retryableErrorCodes") Collection<String> retryableErrorCodes,
            @Param("firstFailureDueAt") LocalDateTime firstFailureDueAt,
            @Param("secondFailureDueAt") LocalDateTime secondFailureDueAt
    );

    @Query("""
            select j from AnalysisJob j
            where j.status = :pending
              and j.errorCode in :errorCodes
            """)
    List<AnalysisJob> findJobsNeedingStatusRecovery(
            @Param("pending") String pending,
            @Param("errorCodes") Collection<String> errorCodes
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from AnalysisJob j where j.id = :id")
    Optional<AnalysisJob> findByIdForUpdate(@Param("id") UUID id);
}
