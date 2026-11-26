-- Local seed for FastAPI test14 clip. Do not apply automatically.
-- Do not run this file against PostgreSQL or RDS from the app.
--
-- DDL sources (script/01.create_table.sql):
--   users: id BIGSERIAL PK, username VARCHAR(30) NOT NULL UNIQUE,
--          email VARCHAR(254) NOT NULL UNIQUE, password VARCHAR(255) NOT NULL,
--          created_at TIMESTAMP NOT NULL  -- no DEFAULT; must be supplied
--   videos: id UUID PK, user_id BIGINT NOT NULL REFERENCES users(id),
--           original_filename VARCHAR(255) NOT NULL,
--           s3_key VARCHAR(1024) NOT NULL UNIQUE,
--           file_size BIGINT NOT NULL, content_type VARCHAR(100) NOT NULL,
--           duration_seconds NUMERIC(10,3) NULL, width INTEGER NULL,
--           height INTEGER NULL, frame_rate NUMERIC(6,3) NULL,
--           recorded_at TIMESTAMP NULL,
--           created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
--           updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
--
-- videos.id uses FastAPI video_config key from the FastAPI contract notes:
--   00000000-0000-4000-8000-000000000014  (clip_key test14)
-- Spring upload() always allocates a new UUID, so a normal /api/videos upload
-- will NOT send this video_id to FastAPI unless video_config is updated.
--
-- content_type is the MOV type already listed in app.video.allowed-content-types
-- (video/quicktime), not a DDL default.
--
-- file_size is NOT NULL and was not measured in this environment
-- (no local test14.MOV, no AWS CLI). Replace FILE_SIZE_BYTES_REQUIRED
-- with the real object size before applying. Do not guess.

INSERT INTO users (username, email, password, created_at)
VALUES (
    'test14',
    'test14@example.com',
    -- BCrypt hash of plaintext "password" (AuthService uses PasswordEncoder).
    '$2a$10$92IXUNpkjO0rOQ5byMi.Ye4oKoEa3Ro9llC/.og/at2.uheWG/igi',
    CURRENT_TIMESTAMP
);

-- Apply only after FILE_SIZE_BYTES_REQUIRED is replaced with a measured bigint.
INSERT INTO videos (
    id,
    user_id,
    original_filename,
    s3_key,
    file_size,
    content_type
)
SELECT
    '00000000-0000-4000-8000-000000000014'::uuid,
    u.id,
    'test14.MOV',
    'test14.MOV',
    FILE_SIZE_BYTES_REQUIRED,
    'video/quicktime'
FROM users u
WHERE u.username = 'test14';
