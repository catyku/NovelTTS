-- ============================================================
-- V4__app_user.sql
-- 管理介面帳號（取代原本寫在 application.yml 的明文密碼）
--
-- 密碼以 BCrypt 雜湊儲存，不保留明文。
-- must_change_password 用於強制首次登入時變更預設密碼。
-- ============================================================

CREATE TABLE app_user (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username             VARCHAR(64)  NOT NULL,
    password_hash        VARCHAR(120) NOT NULL,
    display_name         VARCHAR(128),
    role                 VARCHAR(32)  NOT NULL DEFAULT 'ADMIN',
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    must_change_password BOOLEAN      NOT NULL DEFAULT FALSE,
    last_login_at        TIMESTAMPTZ,
    password_changed_at  TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_app_user_username UNIQUE (username),
    CONSTRAINT ck_app_user_role CHECK (role IN ('ADMIN','VIEWER'))
);

COMMENT ON TABLE  app_user IS '管理介面帳號';
COMMENT ON COLUMN app_user.password_hash IS 'BCrypt 雜湊，非明文';
COMMENT ON COLUMN app_user.must_change_password IS '為 true 時登入後強制導向變更密碼頁';

-- 種子帳號：admin / admin（BCrypt cost 10），must_change_password = true
-- 密碼雜湊由 BCryptPasswordEncoder 產生，與 Spring Security 驗證格式相容。
-- 首次登入後必須變更密碼，之後 must_change_password 設為 false。
INSERT INTO app_user (username, password_hash, display_name, role, must_change_password)
VALUES (
    'admin',
    '$2a$10$1aCevHLksSux8YpbjxAbdO1JpDpoDRp/XFB4jfOTq1ToWyBR61TRy',
    '管理員',
    'ADMIN',
    TRUE
);
