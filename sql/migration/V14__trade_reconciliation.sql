CREATE TABLE t_trade_reconciliation (
    order_id BIGINT NOT NULL,
    kind VARCHAR(8) NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    attempts INT NOT NULL,
    checked_at DATETIME(6) NOT NULL,
    PRIMARY KEY (order_id, kind),
    INDEX idx_reconciliation_checked (checked_at, order_id, kind)
);

INSERT INTO t_admin_permission(code,name) VALUES ('trade:reconcile','支付退款对账');
INSERT INTO t_admin_role_permission(role_id,permission_code) VALUES (1,'trade:reconcile'), (4,'trade:reconcile');
