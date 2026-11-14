package com.xiaoke.order.controller;

import com.xiaoke.common.auth.admin.AdminPermission;
import com.xiaoke.common.auth.admin.AdminPermissions;
import com.xiaoke.common.result.Result;
import com.xiaoke.common.web.AllowedQuery;
import com.xiaoke.order.reconciliation.ReconciliationMapper;
import com.xiaoke.order.reconciliation.ReconciliationService;
import com.xiaoke.order.refund.RefundMapper;
import com.xiaoke.order.refund.RefundOrder;
import com.xiaoke.order.refund.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/orders")
@RequiredArgsConstructor
public class AdminTradeController {
    private final RefundService refunds;
    private final ReconciliationService reconciliation;

    @GetMapping("/refunds")
    @AdminPermission(AdminPermissions.REFUND_REVIEW)
    @AllowedQuery({"status", "orderNo", "page"})
    public Result<List<RefundMapper.Summary>> refunds(@RequestParam(required = false) RefundOrder.State status,
            @RequestParam(required = false) String orderNo, @RequestParam(defaultValue = "1") int page) {
        return Result.success(refunds.list(status, orderNo, page));
    }

    @PostMapping("/{orderNo}/refund/approve")
    @AdminPermission(AdminPermissions.REFUND_REVIEW)
    @AllowedQuery({})
    public Result<RefundService.Status> approve(@PathVariable String orderNo) {
        return Result.success(refunds.review(orderNo, true));
    }

    @PostMapping("/{orderNo}/refund/reject")
    @AdminPermission(AdminPermissions.REFUND_REVIEW)
    @AllowedQuery({})
    public Result<RefundService.Status> reject(@PathVariable String orderNo) {
        return Result.success(refunds.review(orderNo, false));
    }

    @PostMapping("/{orderNo}/refund/retry")
    @AdminPermission(AdminPermissions.REFUND_REVIEW)
    @AllowedQuery({})
    public Result<RefundService.Status> retry(@PathVariable String orderNo) {
        return Result.success(refunds.retry(orderNo));
    }

    @GetMapping("/reconciliation")
    @AdminPermission(AdminPermissions.TRADE_RECONCILE)
    @AllowedQuery({"orderNo", "differences", "page"})
    public Result<List<ReconciliationMapper.Entry>> reconciliation(@RequestParam(required = false) String orderNo,
            @RequestParam(defaultValue = "false") boolean differences, @RequestParam(defaultValue = "1") int page) {
        return Result.success(reconciliation.list(orderNo, differences, page));
    }

    @PostMapping("/reconciliation/run")
    @AdminPermission(AdminPermissions.TRADE_RECONCILE)
    @AllowedQuery({})
    public Result<List<ReconciliationMapper.Entry>> run() {
        return Result.success(reconciliation.runBatch());
    }

    @PostMapping("/{orderNo}/reconciliation/{kind}")
    @AdminPermission(AdminPermissions.TRADE_RECONCILE)
    @AllowedQuery({})
    public Result<ReconciliationMapper.Entry> recheck(@PathVariable String orderNo,
            @PathVariable ReconciliationMapper.Kind kind) {
        return Result.success(reconciliation.recheck(orderNo, kind));
    }
}
