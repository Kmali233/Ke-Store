package com.xiaoke.order.service;
import com.xiaoke.order.adapter.user.OrderUserReadMapper;
import com.xiaoke.order.adapter.user.OrderUserRow;
import com.xiaoke.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class OrderUsers {
    private final OrderUserReadMapper mapper;
    public void check(Long uid) { requireUid(uid); requireActive(mapper.findById(uid)); }
    public void lock(Long uid) { requireUid(uid); requireActive(mapper.lockById(uid)); }
    private void requireUid(Long uid) { if (uid==null || uid<=0) { throw new BusinessException(401,"登录身份已失效"); } }
    private void requireActive(OrderUserRow row) {
        if (row==null || !Integer.valueOf(0).equals(row.getDeleted())) { throw new BusinessException(401,"登录身份已失效"); }
        if (!Integer.valueOf(1).equals(row.getStatus())) { throw new BusinessException(403,"账号已禁用"); }
    }
}
