package com.xiaoke.order.allocator;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.order.mapper.OrdersMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 根据指定日期和数据库已有订单，计算下一个订单号
 */

@Component
@RequiredArgsConstructor
public class OrderNoAllocator {
    private final OrdersMapper mapper;
    /** 不单独提供并发保证，必须由Facade持有按日锁直到整个创建事务结束。 */
    public String next(LocalDate day) {
        String prefix=day.format(DateTimeFormatter.BASIC_ISO_DATE);
        if (!prefix.matches("[0-9]{8}") || mapper.countMalformed(prefix+"%")!=0) { throw new BusinessException(500,"历史订单号异常"); }
        String max=mapper.maxOrderNo(prefix+"000000",prefix+"999999");
        int next=1;
        if (max!=null) {
            if (!max.matches("[0-9]{14}") || !max.startsWith(prefix)) { throw new BusinessException(500,"历史订单号异常"); }
            next=Integer.parseInt(max.substring(8))+1;
        }
        if (next>999999) { throw new BusinessException(409,"当日订单号已耗尽"); }
        return prefix+String.format(Locale.ROOT,"%06d",next);
    }
}
