package com.xiaoke.user.dto;

import com.xiaoke.user.validation.ValidShippingAddress;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 新增、全量修改共用的地址字段；不接受客户端 userId。 */
@Getter
@Setter
@ValidShippingAddress
public abstract class AddressInput {
    @NotBlank @Size(max = 50)
    private String receiverName;
    @NotBlank @Size(max = 20)
    private String receiverPhone;
    @Size(max = 50)
    private String province;
    @Size(max = 50)
    private String city;
    @Size(max = 50)
    private String district;
    @NotBlank @Size(max = 255)
    private String detailAddress;

    public void setReceiverName(String value) { receiverName = trimToNull(value); }
    public void setReceiverPhone(String value) { receiverPhone = trimToNull(value); }
    public void setProvince(String value) { province = trimToNull(value); }
    public void setCity(String value) { city = trimToNull(value); }
    public void setDistrict(String value) { district = trimToNull(value); }
    public void setDetailAddress(String value) { detailAddress = trimToNull(value); }

    private static String trimToNull(String value) {
        if (value == null) { return null; }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
