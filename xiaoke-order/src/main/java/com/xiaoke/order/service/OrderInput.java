package com.xiaoke.order.service;
import com.xiaoke.common.exception.BusinessException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Component @RequiredArgsConstructor
public class OrderInput {
    private final Validator validator;
    public void check(Object dto) {
        if (dto==null || !validator.validate(dto).isEmpty()) { throw new BusinessException(400,"参数错误"); }
    }
    public void orderNo(String no) {
        if (no==null || !no.matches("[0-9]{14}")) { throw new BusinessException(400,"订单号格式不合法"); }
        try {
            if (LocalDate.parse(no.substring(0,8),DateTimeFormatter.BASIC_ISO_DATE).getYear()<1) { throw new DateTimeParseException("year",no,0); }
        } catch (DateTimeParseException invalid) { throw new BusinessException(400,"订单号日期不合法"); }
    }
    public static void rows(int actual,int expected) {
        if (actual!=expected) { throw new BusinessException(500,"写入失败"); }
    }
}
