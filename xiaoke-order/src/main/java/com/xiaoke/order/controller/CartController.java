package com.xiaoke.order.controller;

import com.xiaoke.common.auth.LoginRequired;
import com.xiaoke.common.auth.UserContext;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.common.result.Result;
import com.xiaoke.order.dto.CartCheckoutDTO;
import com.xiaoke.order.service.CartService;
import com.xiaoke.order.service.OrderCommandFacade;
import com.xiaoke.order.vo.CartItemVO;
import com.xiaoke.order.vo.OrderCreateVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/cart")
@LoginRequired
@RequiredArgsConstructor
public class CartController {
    private final CartService cart;
    private final OrderCommandFacade orders;

    public record AddItem(@NotNull @Positive Long skuId, @NotNull @Min(1) @Max(999) Integer quantity) { }
    public record UpdateItem(@Min(1) @Max(999) Integer quantity, Boolean selected) { }

    @GetMapping
    public Result<List<CartItemVO>> list() {
        return Result.success(cart.list(UserContext.requireUserId()));
    }

    @PostMapping("/items")
    public Result<Void> add(@Valid @RequestBody AddItem item) {
        cart.add(UserContext.requireUserId(), item.skuId(), item.quantity());
        return Result.success();
    }

    @PatchMapping("/items/{skuId}")
    public Result<Void> update(@PathVariable Long skuId, @Valid @RequestBody UpdateItem item) {
        cart.update(UserContext.requireUserId(), skuId, item.quantity(), item.selected());
        return Result.success();
    }

    @DeleteMapping("/items/{skuId}")
    public Result<Void> delete(@PathVariable Long skuId) {
        cart.delete(UserContext.requireUserId(), skuId);
        return Result.success();
    }

    @PostMapping("/checkout")
    public Result<OrderCreateVO> checkout(@Valid @RequestBody CartCheckoutDTO dto, HttpServletRequest request) {
        var keys = Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.size() != 1) {
            throw new BusinessException(400, "必须且只能传一次Idempotency-Key");
        }
        return Result.success(orders.checkoutCart(UserContext.requireUserId(), dto.toOrder(), keys.get(0)));
    }
}
