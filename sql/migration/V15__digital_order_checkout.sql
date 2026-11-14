-- Preserve historical address snapshots and logistics records without writing them for new orders.
ALTER TABLE t_orders
    MODIFY receiver_name VARCHAR(50) NULL DEFAULT NULL COMMENT '历史收货人',
    MODIFY receiver_phone VARCHAR(20) NULL DEFAULT NULL COMMENT '历史收货电话',
    MODIFY receiver_address VARCHAR(255) NULL DEFAULT NULL COMMENT '历史收货地址',
    MODIFY status TINYINT NOT NULL DEFAULT 0 COMMENT '0待付款 1已付款 2历史备货 3历史发货 4历史签收 5已取消';

-- Move existing role grants to the narrower permission.
START TRANSACTION;
INSERT INTO t_admin_permission(code,name) VALUES ('order:read','订单查看');
INSERT INTO t_admin_role_permission(role_id,permission_code)
    SELECT role_id,'order:read' FROM t_admin_role_permission WHERE permission_code='order:ship';
DELETE FROM t_admin_role_permission WHERE permission_code='order:ship';
DELETE FROM t_admin_permission WHERE code='order:ship';
COMMIT;
