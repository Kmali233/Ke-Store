package com.xiaoke.order.service;
import com.xiaoke.order.dto.*;
import com.xiaoke.order.vo.OrderCreateVO;
public interface OrderCommandService {
    OrderCreateVO create(Long uid,OrderCreateDTO dto);
    void pay(Long uid,String no);
    void ship(String no,OrderShipDTO dto);
    void cancel(Long uid,String no);
}
