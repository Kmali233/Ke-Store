package com.xiaoke.order.adapter.category;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.order.adapter.product.OrderProductMapper;
import com.xiaoke.order.adapter.product.OrderProductRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

/** order自有分类适配器。
 * 作用：下单时，检查商品以及它所属的整条分类链是否可用，
 * 并通过加锁、重新检查，避免使用已经变化的分类关系继续下单。
 * */
@Component
@RequiredArgsConstructor
public class OrderCategoryReadAdapter {
    private final OrderCategoryReadMapper categories;
    private final OrderProductMapper products;
    public record Discovery(Map<Long,Long> productCategories,Map<Long,OrderCategoryRow> rows) { }

    /** 查出商品所属分类、沿途所有父分类，记下当前结果 */
    public Discovery discover(Collection<Long> productIds) {
        Map<Long,Long> refs=new HashMap<>();
        Map<Long,OrderCategoryRow> rows=new HashMap<>();
        for (Long id:productIds) {
            var product=products.findById(id);
            requireAvailable(product);
            refs.put(id,product.getCategoryId());
            Long cursor=product.getCategoryId();
            var visited=new HashSet<Long>();
            while (cursor!=null && cursor!=0) {
                if (cursor<0 || !visited.add(cursor)) { throw unavailable(); }
                if (!rows.containsKey(cursor)) { rows.put(cursor,categories.findById(cursor)); }
                var row=rows.get(cursor);
                requireAvailable(row);
                cursor=row.getParentId();
                if (cursor==null) { throw unavailable(); }
            }
        }
        return new Discovery(refs,rows);
    }

    /** 按分类 ID 升序加锁，并检查分类有没有变化 */
    public void lockAndVerify(Discovery discovery) {
        for (Long id:new TreeSet<>(discovery.rows().keySet())) {
            var expected=discovery.rows().get(id);
            var current=categories.selectCategoryForUpdate(id);
            if (current==null || !Objects.equals(expected.getParentId(),current.getParentId())
                    || !Objects.equals(expected.getStatus(),current.getStatus())
                    || !Objects.equals(expected.getDeleted(),current.getDeleted())) {
                throw new BusinessException(409,"分类结构已变化");
            }
            requireAvailable(current);
        }
    }

    /** 商品加锁后，检查它是否仍然可用、是否换了分类 */
    public void verifyProduct(Discovery discovery,OrderProductRow row) {
        requireAvailable(row);
        if (!Objects.equals(discovery.productCategories().get(row.getId()),row.getCategoryId())) {
            throw new BusinessException(409,"商品分类已变化");
        }
    }


    /** 检查分类是否可用 */
    private void requireAvailable(OrderCategoryRow row) {
        if (row==null || !Integer.valueOf(0).equals(row.getDeleted()) || !Integer.valueOf(1).equals(row.getStatus())) { throw unavailable(); }
    }


    /** 检查商品是否可用 */
    private void requireAvailable(OrderProductRow row) {
        if (row==null || !Integer.valueOf(0).equals(row.getDeleted()) || !Integer.valueOf(1).equals(row.getStatus())) { throw unavailable(); }
    }

    /** 分类不可用时抛出的异常 */
    private BusinessException unavailable() {
        return new BusinessException(404,"商品不可用");
    }
}
