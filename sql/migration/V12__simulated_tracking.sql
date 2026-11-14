CREATE TABLE t_order_tracking_event (
    order_id BIGINT NOT NULL,
    step TINYINT NOT NULL,
    occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (order_id, step),
    CONSTRAINT ck_order_tracking_step CHECK (step BETWEEN 1 AND 3)
);
