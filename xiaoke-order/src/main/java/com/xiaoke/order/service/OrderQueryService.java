package com.xiaoke.order.service;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xiaoke.order.dto.OrderQueryDTO;
import com.xiaoke.order.vo.*;
public interface OrderQueryService {
    IPage<OrderSummaryVO> page(Long uid,OrderQueryDTO query);
    OrderDetailVO detail(Long uid,String no);
    OrderStatusVO status(Long uid,String no);
}
