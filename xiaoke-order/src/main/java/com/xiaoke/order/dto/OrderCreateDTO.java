package com.xiaoke.order.dto;
import com.xiaoke.order.validation.ValidOrderCreate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.List;

/** 收货字段记录是否出现在JSON中，区分省略和显式null。 */
@Getter @Setter
@ValidOrderCreate
public class OrderCreateDTO {
    @NotNull @Size(min=1,max=20)
    private List<@NotNull @Valid OrderItemCreateDTO> items;
    @Positive private Long addressId;
    @Size(max=50) @Pattern(regexp="(?s).*\\S.*") private String receiverName;
    @Size(max=20) @Pattern(regexp="(?s).*\\S.*") private String receiverPhone;
    @Size(max=255) @Pattern(regexp="(?s).*\\S.*") private String receiverAddress;
    @Size(max=255) private String remark;
    @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private boolean addressProvided, nameProvided, phoneProvided, locationProvided;

    public void setAddressId(Long value) { addressProvided=true; addressId=value; }
    public void setReceiverName(String value) { nameProvided=true; receiverName=trim(value); }
    public void setReceiverPhone(String value) { phoneProvided=true; receiverPhone=trim(value); }
    public void setReceiverAddress(String value) { locationProvided=true; receiverAddress=trim(value); }
    public void setRemark(String value) { remark=value==null || value.trim().isEmpty() ? null : value.trim(); }
    public boolean validShippingSource() {
        if (addressProvided) { return addressId!=null && !nameProvided && !phoneProvided && !locationProvided; }
        return nameProvided && phoneProvided && locationProvided && receiverName!=null && receiverPhone!=null && receiverAddress!=null;
    }
    private String trim(String value) { return value==null ? null : value.trim(); }
}
