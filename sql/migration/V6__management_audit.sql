CREATE TABLE t_admin_audit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_type VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id BIGINT NULL,
    action VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_type VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'SUCCESS',
    changes_json JSON NOT NULL,
    trace_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    UNIQUE KEY uk_audit_event(event_key),
    KEY idx_audit_time(created_at,id),
    KEY idx_audit_actor(actor_id,created_at,id),
    KEY idx_audit_target(target_type,target_id,created_at,id),
    KEY idx_audit_trace(trace_id),
    CONSTRAINT ck_audit_result CHECK(result='SUCCESS'),
    CONSTRAINT ck_audit_actor CHECK((actor_type='ADMIN' AND actor_id IS NOT NULL AND actor_id>0) OR (actor_type='SYSTEM' AND actor_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理操作成功审计，时间为UTC，非不可篡改存储';
INSERT INTO t_admin_permission(code,name) VALUES('audit:read','管理审计查询');
INSERT INTO t_admin_role_permission(role_id,permission_code) VALUES(1,'audit:read');
