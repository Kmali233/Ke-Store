package com.xiaoke.order.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xiaoke.common.auth.admin.AdminPermission;
import com.xiaoke.common.auth.admin.AdminPermissions;
import com.xiaoke.common.result.Result;
import com.xiaoke.common.web.AllowedQuery;
import com.xiaoke.order.dto.AdminOrderQueryDTO;
import com.xiaoke.order.service.OrderQueryService;
import com.xiaoke.order.vo.AdminOrderDetailVO;
import com.xiaoke.order.vo.OrderSummaryVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {
    private final OrderQueryService queries;

    @GetMapping("/page")
    @AdminPermission(AdminPermissions.ORDER_SHIP)
    @AllowedQuery({"pageNum", "pageSize", "status", "orderNo"})
    public Result<IPage<OrderSummaryVO>> page(@Valid AdminOrderQueryDTO query) {
        return Result.success(queries.pageForAdmin(query));
    }

    @GetMapping("/{orderNo}")
    @AdminPermission(AdminPermissions.ORDER_SHIP)
    @AllowedQuery({})
    public Result<AdminOrderDetailVO> detail(@PathVariable String orderNo) {
        return Result.success(queries.detailForAdmin(orderNo));
    }
}
