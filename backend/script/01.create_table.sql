CREATE TABLE public.users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(30) NOT NULL UNIQUE,
    email VARCHAR(254) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

ALTER TABLE users OWNER TO tvea;



CREATE TABLE videos (
                        id UUID PRIMARY KEY,

                        user_id BIGINT NOT NULL,

                        original_filename VARCHAR(255) NOT NULL,
                        s3_key VARCHAR(1024) NOT NULL UNIQUE,

                        file_size BIGINT NOT NULL,
                        content_type VARCHAR(100) NOT NULL,

                        duration_seconds NUMERIC(10,3),
                        width INTEGER,
                        height INTEGER,
                        frame_rate NUMERIC(6,3),

                        recorded_at TIMESTAMP,

                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                        CONSTRAINT fk_videos_user
                            FOREIGN KEY (user_id)
                                REFERENCES users(id)
);

ALTER TABLE videos OWNER TO tvea;

CREATE INDEX idx_videos_user_id
    ON videos(user_id);










CREATE TABLE   (
                               id UUID PRIMARY KEY,

                               video_id UUID NOT NULL,

                               status VARCHAR(20) NOT NULL DEFAULT 'PENDING',

                               started_at TIMESTAMP,
                               finished_at TIMESTAMP,

                               retry_count INTEGER NOT NULL DEFAULT 0,

                               error_code VARCHAR(100),
                               error_message TEXT,

                               created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                               updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                               CONSTRAINT fk_analysis_jobs_video
                                   FOREIGN KEY (video_id)
                                       REFERENCES videos(id),

                               CONSTRAINT chk_analysis_jobs_status
                                   CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

ALTER TABLE analysis_jobs OWNER TO tvea;
CREATE INDEX idx_analysis_jobs_video_id
    ON analysis_jobs(video_id);

CREATE INDEX idx_analysis_jobs_status
    ON analysis_jobs(status);

COMMENT ON TABLE analysis_jobs IS '영상 분석 작업(Job) 및 처리 상태 이력';

COMMENT ON COLUMN analysis_jobs.id IS '분석 Job ID';
COMMENT ON COLUMN analysis_jobs.video_id IS '분석 대상 영상 (videos.id)';
COMMENT ON COLUMN analysis_jobs.status IS '작업 상태: PENDING / PROCESSING / COMPLETED / FAILED';
COMMENT ON COLUMN analysis_jobs.started_at IS '분석 시작 시각';
COMMENT ON COLUMN analysis_jobs.finished_at IS '분석 종료 시각 (성공/실패 공통)';
COMMENT ON COLUMN analysis_jobs.retry_count IS '자동 재시도 횟수';
COMMENT ON COLUMN analysis_jobs.error_code IS '실패 코드';
COMMENT ON COLUMN analysis_jobs.error_message IS '실패 메시지';
COMMENT ON COLUMN analysis_jobs.created_at IS 'Job 생성(분석 요청) 시각';
COMMENT ON COLUMN analysis_jobs.updated_at IS '최종 수정 시각';