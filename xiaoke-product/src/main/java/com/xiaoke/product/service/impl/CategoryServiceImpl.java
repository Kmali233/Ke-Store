package com.xiaoke.product.service.impl;

import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.product.enums.CatalogStatus;
import com.xiaoke.common.result.ResultCode;
import com.xiaoke.product.dto.*;
import com.xiaoke.product.entity.Category;
import com.xiaoke.product.mapper.*;
import com.xiaoke.product.service.*;
import com.xiaoke.product.vo.CategoryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.xiaoke.product.service.CatalogValidation.oneRow;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {
    private final CategoryMapper categories;
    private final ProductMapper products;
    private final CategoryChainPolicy chains;
    private final CatalogTransactions transactions;
    private final CatalogValidation validation;

    @Override
    public List<CategoryVO> listVisibleCategories() {
        return transactions.read(() -> {
            var rows = categories.listActive();
            var visible = chains.visibleIds(rows);
            return rows.stream().filter(row -> visible.contains(row.getId()))
                    .sorted(Comparator.comparing(Category::getParentId).thenComparing(Category::getSort).thenComparing(Category::getId))
                    .map(row -> new CategoryVO(row.getId(), row.getName(), row.getParentId(), row.getIcon(), row.getSort())).toList();
        });
    }

    @Override
    public void addCategory(CategoryCreateDTO dto) {
        validation.check(dto);
        transactions.write(() -> {
            long parent = dto.getParentId() == null ? 0 : dto.getParentId();
            var locked = chains.lockChains(parent);
            chains.requireUsable(parent, locked, null);
            Category row = new Category();
            BeanUtils.copyProperties(dto, row);
            row.setParentId(parent);
            row.setSort(dto.getSort() == null ? 0 : dto.getSort());
            row.setStatus(dto.getStatus() == null ? CatalogStatus.ENABLED.value() : dto.getStatus());
            row.setDeleted(0);
            oneRow(categories.insert(row));
            return null;
        });
    }

    @Override
    public void updateCategory(CategoryUpdateDTO dto) {
        validation.check(dto);
        if (Objects.equals(dto.getId(), dto.getParentId())) { throw new BusinessException(ResultCode.PARAM_ERROR); }
        transactions.write(() -> {
            var locked = chains.lockChains(dto.getId(), dto.getParentId());
            Category row = requireActive(locked.get(dto.getId()));
            Category updated = new Category();
            BeanUtils.copyProperties(row, updated);
            if (dto.getName() != null) { updated.setName(dto.getName()); }
            if (dto.getParentId() != null) { updated.setParentId(dto.getParentId()); }
            if (dto.getIcon() != null) { updated.setIcon(dto.getIcon()); }
            if (dto.getSort() != null) { updated.setSort(dto.getSort()); }
            if (dto.getStatus() != null) { updated.setStatus(dto.getStatus()); }
            if (dto.getParentId() != null || (CatalogStatus.ENABLED.matches(updated.getStatus()) && !Objects.equals(row.getStatus(), updated.getStatus()))) {
                chains.requireUsable(updated.getParentId(), locked, row.getId());
            }
            if (!row.equals(updated)) { oneRow(categories.updateEditableFields(updated)); }
            return null;
        });
    }

    @Override
    public void deleteCategory(Long id) {
        validation.id(id);
        transactions.write(() -> {
            requireActive(chains.lockChains(id).get(id));
            if (categories.countActiveChildren(id) > 0 || products.countActiveByCategory(id) > 0) {
                throw new BusinessException(409, "分类仍有有效子分类或商品");
            }
            oneRow(categories.markDeleted(id));
            return null;
        });
    }

    private Category requireActive(Category row) {
        if (!CategoryChainPolicy.active(row)) { throw new BusinessException(ResultCode.NOT_FOUND); }
        return row;
    }
}
