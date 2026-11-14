package com.xiaoke.common.auth.admin;

import java.util.Set;

public final class AdminPermissions {
    public static final String MANAGE = "admin:manage";
    public static final String PRODUCT_WRITE = "product:write";
    public static final String INVENTORY_ADJUST = "inventory:adjust";
    public static final String ORDER_READ = "order:read";
    public static final String REFUND_REVIEW = "refund:review";
    public static final String TRADE_RECONCILE = "trade:reconcile";
    public static final String AUDIT_READ = "audit:read";
    public static final Set<String> ALL = Set.of(MANAGE, PRODUCT_WRITE, INVENTORY_ADJUST, ORDER_READ, REFUND_REVIEW, TRADE_RECONCILE, AUDIT_READ);
    private AdminPermissions() { }
}
