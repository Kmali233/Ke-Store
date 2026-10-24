package com.xiaoke.product.enums;

/** 商品上/下架、分类启用/停用使用相同的数据库编码。 */
public enum CatalogStatus {
    /**
     * CatalogStatus.DISABLED	0	下架	停用
     * CatalogStatus.ENABLED	1	上架	启用
     */
    DISABLED(0), ENABLED(1);

    private final int value;

    CatalogStatus(int value) {
        this.value = value;

    }
    public int value() {
        return value;
    }

    /**
     * 判断一个数字是不是这个状态
     * @param candidate
     * @return
     */
    public boolean matches(Integer candidate) {
        return candidate != null && candidate == value;
    }
}
