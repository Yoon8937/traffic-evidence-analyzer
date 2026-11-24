-- Incremental migration for dispatch in-flight state and lease fencing tokens.
-- Do not recreate analysis_jobs. Apply manually; this file is not executed by the app.
-- Apply after 02.alter_analysis_jobs_scheduler_lease.sql.

ALTER TABLE analysis_jobs
    ADD COLUMN IF NOT EXISTS scheduler_lease_token VARCHAR(64),
    ADD COLUMN IF NOT EXISTS dispatch_inflight BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN analysis_jobs.scheduler_lease_token IS '스케줄러 선점 fencing token. 선점마다 새로 발급되며 이전 소유자의 쓰기를 거부한다';
COMMENT ON COLUMN analysis_jobs.dispatch_inflight IS '재시도 예약 후 HTTP 응답 확정 전. true이면 미전송/무응답 시도를 소진으로 처리하지 않는다';
