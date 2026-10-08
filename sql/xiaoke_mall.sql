-- 小Ke商城 初始化脚本
CREATE DATABASE IF NOT EXISTS xiaoke_mall DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE xiaoke_mall;

-- 商品表
DROP TABLE IF EXISTS t_product;
CREATE TABLE t_product (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '商品ID',
    name        VARCHAR(128) NOT NULL COMMENT '商品名称',
    category_id BIGINT       DEFAULT NULL COMMENT '分类ID',
    price       DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '价格',
    stock       INT          NOT NULL DEFAULT 0 COMMENT '库存',
    image       VARCHAR(255) DEFAULT NULL COMMENT '商品图片',
    description VARCHAR(512) DEFAULT NULL COMMENT '描述',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '0下架 1上架',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '0未删 1已删',
    PRIMARY KEY (id),
    KEY idx_category (category_id),
    KEY idx_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';

INSERT INTO t_product (name, category_id, price, stock, description, status)
VALUES ('示例商品', 1, 99.00, 100, '用于验证框架的示例商品', 1);
