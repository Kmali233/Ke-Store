package com.xiaoke.product.service;

import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.product.enums.CatalogStatus;
import com.xiaoke.common.result.ResultCode;
import com.xiaoke.product.entity.Category;
import com.xiaoke.product.mapper.CategoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

/** 所有分类引用写入遵守同一规则：发现链 → 按 ID 升序锁定 → 重核链。 */
@Component
@RequiredArgsConstructor
public class CategoryChainPolicy {
    private final CategoryMapper mapper;

    public Set<Long> visibleIds(List<Category> rows) {
        Map<Long, Category> index = new HashMap<>();
        rows.forEach(row -> index.put(row.getId(), row));
        Set<Long> visible = new HashSet<>();
        for (Category row : rows) {
            Set<Long> visited = new HashSet<>();
            Long cursor = row.getId();
            while (cursor != null && cursor != 0 && visited.add(cursor)) {
                Category current = index.get(cursor);
                if (!active(current) || !CatalogStatus.ENABLED.matches(current.getStatus())) { break; }
                cursor = current.getParentId();
            }
            if (Long.valueOf(0).equals(cursor)) { visible.add(row.getId()); }
        }
        return visible;
    }

    /** 必须在写事务内调用；包括旧引用的已删除/缺失节点，以允许清理历史坏引用。 */
    public Map<Long, Category> lockChains(Long... starts) {
        Map<Long, Category> discovered = new HashMap<>();
        for (Long start : starts) {
            Set<Long> visited = new HashSet<>();
            Long cursor = start;
            while (cursor != null && cursor > 0 && visited.add(cursor)) {
                if (!discovered.containsKey(cursor)) { discovered.put(cursor, mapper.findIncludingDeleted(cursor)); }
                Category row = discovered.get(cursor);
                if (row == null) { break; }
                cursor = row.getParentId();
            }
        }
        Map<Long, Category> locked = new HashMap<>();
        for (Long id : new TreeSet<>(discovered.keySet())) { locked.put(id, mapper.lockById(id)); }
        for (Long id : discovered.keySet()) {
            Category before = discovered.get(id), after = locked.get(id);
            if (!sameStructure(before, after)) {
                throw new BusinessException(409, "分类结构已变化，请重新读取");
            }
        }
        return locked;
    }

    /** 新引用必须从起点一直到根都存在且启用；forbidden 防止改父级后成环。 */
    public void requireUsable(Long start, Map<Long, Category> locked, Long forbidden) {
        Set<Long> visited = new HashSet<>();
        Long cursor = start;
        while (cursor != null && cursor != 0) {
            if (cursor < 0 || Objects.equals(cursor, forbidden) || !visited.add(cursor)) {
                throw new BusinessException(409, "分类引用形成循环");
            }
            Category row = locked.get(cursor);
            if (!active(row)) { throw new BusinessException(ResultCode.NOT_FOUND); }
            if (!CatalogStatus.ENABLED.matches(row.getStatus())) { throw new BusinessException(409, "分类链未启用"); }
            cursor = row.getParentId();
            if (cursor == null) { throw new BusinessException(409, "分类结构无效"); }
        }
    }

    public static boolean active(Category row) { return row != null && Integer.valueOf(0).equals(row.getDeleted()); }

    private boolean sameStructure(Category a, Category b) {
        if (a == null || b == null) { return a == b; }
        return Objects.equals(a.getParentId(), b.getParentId())
                && Objects.equals(a.getDeleted(), b.getDeleted()) && Objects.equals(a.getStatus(), b.getStatus());
    }
}
