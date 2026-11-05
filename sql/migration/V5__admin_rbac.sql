-- Fixed permission vocabulary; administration owns all relationship writes.
CREATE TABLE t_admin_role (
    id BIGINT NOT NULL PRIMARY KEY,
    code VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE t_admin_permission (
    code VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    name VARCHAR(100) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE t_admin_role_permission (
    role_id BIGINT NOT NULL,
    permission_code VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY(role_id,permission_code),
    CONSTRAINT fk_rp_role FOREIGN KEY(role_id) REFERENCES t_admin_role(id),
    CONSTRAINT fk_rp_permission FOREIGN KEY(permission_code) REFERENCES t_admin_permission(code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE t_admin_account_role (
    admin_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY(admin_id,role_id),
    KEY idx_ar_role(role_id,admin_id),
    CONSTRAINT fk_ar_account FOREIGN KEY(admin_id) REFERENCES t_admin_account(id),
    CONSTRAINT fk_ar_role FOREIGN KEY(role_id) REFERENCES t_admin_role(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO t_admin_role(id,code,name) VALUES
 (1,'system_admin','系统管理员'),(2,'product_admin','商品管理员'),
 (3,'fulfillment','履约人员'),(4,'after_sales','售后人员');
INSERT INTO t_admin_permission(code,name) VALUES
 ('admin:manage','后台账号与角色管理'),('product:write','商品及分类维护'),
 ('inventory:adjust','库存调整'),('order:ship','订单发货'),('refund:review','退款审核');
INSERT INTO t_admin_role_permission(role_id,permission_code)
 SELECT 1,code FROM t_admin_permission;
INSERT INTO t_admin_role_permission(role_id,permission_code) VALUES
 (2,'product:write'),(3,'inventory:adjust'),(3,'order:ship'),(4,'refund:review');
-- Upgrade grants only the earliest active existing account; never grant all historical accounts.
INSERT INTO t_admin_account_role(admin_id,role_id)
 SELECT MIN(id),1 FROM t_admin_account WHERE status=1 AND deleted=0 HAVING MIN(id) IS NOT NULL;
