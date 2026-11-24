-- Incremental migration for analysis job scheduler leases.
-- Do not recreate analysis_jobs. Apply manually; this file is not executed by the app.

ALTER TABLE analysis_jobs
    ADD COLUMN IF NOT EXISTS scheduler_lease_until TIMESTAMP,
    ADD COLUMN IF NOT EXISTS scheduler_lease_owner VARCHAR(64),
    ADD COLUMN IF NOT EXISTS scheduler_lease_task VARCHAR(32);

COMMENT ON COLUMN analysis_jobs.scheduler_lease_until IS '스케줄러 Job 선점 lease 만료 시각. 만료 전에는 다른 인스턴스가 선점하지 않음';
COMMENT ON COLUMN analysis_jobs.scheduler_lease_owner IS '선점한 스케줄러 인스턴스 식별자';
COMMENT ON COLUMN analysis_jobs.scheduler_lease_task IS '선점 목적: DISPATCH / RECOVERY / TIMEOUT';

CREATE INDEX IF NOT EXISTS idx_analysis_jobs_scheduler_lease
    ON analysis_jobs (status, scheduler_lease_until);
