-- Only for guarded isolated verification databases. No production initialization.
CREATE TABLE t_category (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '分类ID',
    name        VARCHAR(50)  NOT NULL COMMENT '分类名称',
    parent_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '父分类ID，0 表示一级分类',
    icon        VARCHAR(255) DEFAULT NULL COMMENT '分类图标',
    sort        INT          NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1启用 0停用',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    KEY idx_parent_id (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品分类表';

CREATE TABLE t_product (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '商品ID',
    name        VARCHAR(128)  NOT NULL COMMENT '商品名称',
    category_id BIGINT        DEFAULT NULL COMMENT '分类ID',
    price       DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '售价',
    stock       INT           NOT NULL DEFAULT 0 COMMENT '库存',
    sales       INT           NOT NULL DEFAULT 0 COMMENT '下单件数：下单+、取消-（已创建且未取消）',
    -- [CR-005 · 2026-10-10] 列名保留 sales，但语义不是销量/累计销量；对外字段与 Java 字段统一为 orderCount（@TableField("sales")）
    image       VARCHAR(255)  DEFAULT NULL COMMENT '商品主图',
    description TEXT          DEFAULT NULL COMMENT '商品描述（可供 AI 引用）',
    status      TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：0下架 1上架',
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    KEY idx_category_id (category_id),
    KEY idx_name (name),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';

CREATE TABLE t_product_attr (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '参数ID',
    product_id  BIGINT       NOT NULL COMMENT '所属商品ID',
    attr_name   VARCHAR(50)  NOT NULL COMMENT '参数名，如 电池容量',
    attr_value  VARCHAR(255) NOT NULL COMMENT '参数值，如 5000mAh',
    attr_group  VARCHAR(50)  DEFAULT NULL COMMENT '参数分组，如 屏幕/性能/续航',
    sort        INT          NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    KEY idx_product_id (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品参数表';
