package com.xiaoke.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CartCheckoutDTO(@Positive Long addressId, @Size(max = 255) String remark,
                              @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid Item> items) {
    public record Item(@NotNull @Positive Long skuId, @NotNull @Min(1) @Max(999) Integer quantity) { }

    public OrderCreateDTO toOrder() {
        var order = new OrderCreateDTO();
        if (addressId != null) { order.setAddressId(addressId); }
        order.setRemark(remark);
        order.setItems(items.stream().map(item -> {
            var line = new OrderItemCreateDTO();
            line.setSkuId(item.skuId());
            line.setQuantity(item.quantity());
            return line;
        }).toList());
        return order;
    }
}
