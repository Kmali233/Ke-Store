CREATE TABLE t_cart_item (
    user_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    selected TINYINT NOT NULL DEFAULT 1,
    PRIMARY KEY (user_id, sku_id),
    CONSTRAINT chk_cart_quantity CHECK (quantity BETWEEN 1 AND 999),
    CONSTRAINT chk_cart_selected CHECK (selected IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='买家购物车SKU清单';
