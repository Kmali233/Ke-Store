-- No default account or password. Provision explicitly from the user service.
CREATE TABLE t_admin_account (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    password VARCHAR(60) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    deleted TINYINT NOT NULL DEFAULT 0,
    token_version BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_admin_username (username),
    CONSTRAINT ck_admin_status CHECK (status IN (0,1)),
    CONSTRAINT ck_admin_deleted CHECK (deleted IN (0,1)),
    CONSTRAINT ck_admin_version CHECK (token_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='独立后台身份与令牌撤销版本';
