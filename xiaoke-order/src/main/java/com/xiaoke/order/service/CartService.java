package com.xiaoke.order.service;

import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.order.adapter.category.OrderCategoryReadAdapter;
import com.xiaoke.order.adapter.product.OrderSkuMapper;
import com.xiaoke.order.dto.OrderItemCreateDTO;
import com.xiaoke.order.mapper.CartMapper;
import com.xiaoke.order.vo.CartItemVO;
import com.xiaoke.order.vo.CartItemVO.Availability;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.xiaoke.order.service.OrderInput.rows;

@Service
@RequiredArgsConstructor
public class CartService {
    private final CartMapper cart;
    private final OrderSkuMapper skus;
    private final OrderCategoryReadAdapter categories;
    private final OrderUsers users;
    private final OrderTransactions transactions;

    public List<CartItemVO> list(Long uid) {
        return transactions.read(() -> {
            users.check(uid);
            var lines = cart.list(uid);
            var available = categories.availableCategories(lines.stream().map(CartMapper.CatalogLine::categoryId)
                    .filter(Objects::nonNull).distinct().toList());
            return lines.stream().map(line -> new CartItemVO(line.skuId(), line.quantity(), line.selected(),
                    line.productId(), line.productName(), line.skuName(), line.skuSpec(), line.image(),
                    line.price(), line.stock(), availability(line, available))).toList();
        });
    }

    public void add(Long uid, Long skuId, int quantity) {
        transactions.write(() -> {
            users.lock(uid);
            var sku = skus.find(skuId);
            if (sku == null || !active(sku.getStatus(), sku.getDeleted())) {
                throw new BusinessException(404, "SKU不可用");
            }
            categories.discover(List.of(sku.getProductId()));
            Integer current = cart.quantity(uid, skuId);
            if (current == null) {
                rows(cart.insert(uid, skuId, quantity), 1);
            } else {
                int merged = current + quantity;
                if (merged > 999) {
                    throw new BusinessException(409, "同一SKU数量不能超过999");
                }
                rows(cart.update(uid, skuId, merged, null), 1);
            }
            return null;
        });
    }

    public void update(Long uid, Long skuId, Integer quantity, Boolean selected) {
        if (quantity == null && selected == null) {
            throw new BusinessException(400, "请提供数量或勾选状态");
        }
        transactions.write(() -> {
            users.lock(uid);
            if (cart.quantity(uid, skuId) == null) {
                throw missing();
            }
            cart.update(uid, skuId, quantity, selected);
            return null;
        });
    }

    public void delete(Long uid, Long skuId) {
        transactions.write(() -> {
            users.lock(uid);
            if (cart.delete(uid, skuId) != 1) {
                throw missing();
            }
            return null;
        });
    }

    // Called by the order creation transaction while it owns the same user lock as all cart writers.
    void requireSelection(Long uid, List<OrderItemCreateDTO> items) {
        var expected = items.stream().map(item -> new CartMapper.Line(item.getSkuId(), item.getQuantity()))
                .sorted(Comparator.comparing(CartMapper.Line::skuId)).toList();
        if (!cart.selection(uid).equals(expected)) {
            throw new BusinessException(409, "购物车勾选项或数量已变化，请刷新后重新确认");
        }
    }

    void clearSelection(Long uid, int expected) {
        rows(cart.clearSelection(uid), expected);
    }

    private Availability availability(CartMapper.CatalogLine line, Set<Long> availableCategories) {
        if (line.productId() == null || !Integer.valueOf(0).equals(line.skuDeleted())
                || line.productDeleted() == null || line.productDeleted() != 0) {
            return Availability.INVALID_SKU;
        }
        if (!active(line.skuStatus(), line.skuDeleted()) || !active(line.productStatus(), line.productDeleted())
                || (line.categoryId() != null && !availableCategories.contains(line.categoryId()))) {
            return Availability.OFF_SHELF;
        }
        return line.stock() < line.quantity() ? Availability.OUT_OF_STOCK : Availability.AVAILABLE;
    }

    private boolean active(Integer status, Integer deleted) {
        return Integer.valueOf(1).equals(status) && Integer.valueOf(0).equals(deleted);
    }

    private BusinessException missing() {
        return new BusinessException(404, "购物车条目不存在");
    }
}
