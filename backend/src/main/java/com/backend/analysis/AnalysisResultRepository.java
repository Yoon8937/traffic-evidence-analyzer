package com.backend.analysis;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AnalysisResultRepository extends JpaRepository<AnalysisResult, UUID> {

    Optional<AnalysisResult> findByAnalysisJobId(UUID analysisJobId);
}
