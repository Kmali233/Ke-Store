package com.xiaoke.order.dto;
import jakarta.validation.constraints.*;
import lombok.Getter;
@Getter
public class OrderShipDTO {
    @NotBlank @Size(max=50) private String logisticsCompany;
    @NotBlank @Size(max=64) private String trackingNo;
    public void setLogisticsCompany(String value) { logisticsCompany=value==null ? null : value.trim(); }
    public void setTrackingNo(String value) { trackingNo=value==null ? null : value.trim(); }
}
