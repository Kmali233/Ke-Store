package com.xiaoke.product.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.product.enums.CatalogStatus;
import com.xiaoke.common.result.ResultCode;
import com.xiaoke.common.web.PageQuery;
import com.xiaoke.product.dto.*;
import com.xiaoke.product.entity.Product;
import com.xiaoke.product.mapper.*;
import com.xiaoke.product.service.*;
import com.xiaoke.product.vo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.xiaoke.product.service.CatalogValidation.oneRow;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {
    private final ProductMapper products;
    private final CategoryMapper categories;
    private final ProductAttrMapper attrs;
    private final CategoryChainPolicy chains;
    private final CatalogTransactions transactions;
    private final CatalogValidation validation;
    private final SkuService skus;

    @Override
    public IPage<ProductVO> pageProducts(ProductQueryDTO query) {
        validation.check(query);
        query.validatePage();
        return page(query, query.getCategoryId(), query.getName());
    }

    @Override
    public IPage<ProductVO> searchProducts(ProductSearchDTO query) {
        validation.check(query);
        query.validatePage();
        return page(query, null, query.getKeyword());
    }

    private IPage<ProductVO> page(PageQuery query, Long categoryId, String term) {
        return transactions.read(() -> {
            Set<Long> visible = chains.visibleIds(categories.listActive());
            String pattern = literalPattern(term);
            long total = products.countVisible(visible, categoryId, pattern);
            Page<ProductVO> page = new Page<>(query.getPageNum(), query.getPageSize(), total);
            long offset = Math.multiplyExact(query.getPageNum() - 1, query.getPageSize());
            page.setRecords(offset >= total ? List.of() : products.listVisible(visible, categoryId, pattern, offset, query.getPageSize())
                    .stream().map(this::toVO).toList());
            return page;
        });
    }

    @Override
    public ProductDetailVO getProductById(Long id) {
        validation.id(id);
        // 商品、分类和参数在同一 RR 快照内读取，不会读到参数覆盖的中间状态。
        return transactions.read(() -> {
            Product product = requireActive(products.findIncludingDeleted(id));
            Set<Long> visible = chains.visibleIds(categories.listActive());
            if (!CatalogStatus.ENABLED.matches(product.getStatus())
                    || (product.getCategoryId() != null && !visible.contains(product.getCategoryId()))) {
                throw new BusinessException(ResultCode.NOT_FOUND);
            }
            ProductDetailVO detail = new ProductDetailVO();
            BeanUtils.copyProperties(toVO(product), detail);
            detail.setAttrs(attrs.selectActiveByProductId(id).stream()
                    .map(row -> new ProductAttrVO(row.getAttrName(), row.getAttrValue(), row.getAttrGroup(), row.getSort())).toList());
            detail.setSkus(skus.list(id,true));
            return detail;
        });
    }

    @Override
    public void addProduct(ProductCreateDTO dto) {
        validation.check(dto);
        transactions.write(() -> {
            var locked = chains.lockChains(dto.getCategoryId());
            chains.requireUsable(dto.getCategoryId(), locked, null);
            Product row = new Product();
            BeanUtils.copyProperties(dto, row);
            row.setStatus(dto.getStatus() == null ? CatalogStatus.ENABLED.value() : dto.getStatus());
            row.setOrderCount(0);
            row.setDeleted(0);
            oneRow(products.insert(row));
            skus.createDefault(row);
            return null;
        });
    }

    @Override
    public void updateProduct(ProductUpdateDTO dto) {
        validation.check(dto);
        transactions.write(() -> {
            Product before = requireActive(products.findIncludingDeleted(dto.getId()));
            var locked = chains.lockChains(before.getCategoryId(), dto.getCategoryId());
            Product row = lockStableProduct(before);
            var defaultSku=skus.lockDefault(row.getId());
            if (dto.getStock() != null && !dto.getStock().equals(defaultSku.getStock())) {
                throw new BusinessException(409, "不允许通过资料更新覆盖库存");
            }
            Product updated = new Product();
            BeanUtils.copyProperties(row, updated);
            if (dto.getName() != null) { updated.setName(dto.getName()); }
            if (dto.getCategoryId() != null) { updated.setCategoryId(dto.getCategoryId()); }
            // 用数值比较，1 与 1.00 应是同一次幂等更新。
            skus.updateDefault(defaultSku,dto.getPrice());
            if (dto.getImage() != null) { updated.setImage(dto.getImage()); }
            if (dto.getDescription() != null) { updated.setDescription(dto.getDescription()); }
            if (dto.getStatus() != null) { updated.setStatus(dto.getStatus()); }
            if (dto.getCategoryId() != null || (CatalogStatus.ENABLED.matches(updated.getStatus()) && !Objects.equals(row.getStatus(), updated.getStatus()))) {
                chains.requireUsable(updated.getCategoryId(), locked, null);
            }
            if (!row.equals(updated)) { oneRow(products.updateEditableFields(updated)); }
            return null;
        });
    }

    @Override
    public void deleteProduct(Long id) {
        validation.id(id);
        transactions.write(() -> {
            Product before = requireActive(products.findIncludingDeleted(id));
            chains.lockChains(before.getCategoryId());
            lockStableProduct(before);
            oneRow(products.markDeleted(id));
            attrs.softDeleteByProductId(id);
            return null;
        });
    }

    private Product lockStableProduct(Product before) {
        Product locked = requireActive(products.lockById(before.getId()));
        if (!Objects.equals(before.getCategoryId(), locked.getCategoryId())) {
            // 已持有商品锁，不能继续补取分类锁；整笔回滚后由调用方重新读取。
            throw new BusinessException(409, "商品分类已变化，请重新读取");
        }
        return locked;
    }

    private Product requireActive(Product row) {
        if (row == null || !Integer.valueOf(0).equals(row.getDeleted())) { throw new BusinessException(ResultCode.NOT_FOUND); }
        return row;
    }

    private ProductVO toVO(Product row) {
        ProductVO vo = new ProductVO();
        BeanUtils.copyProperties(row, vo);
        var totals=skus.aggregate(row.getId());
        if(totals.invalid()!=0 || totals.defaults()!=1) throw new BusinessException(500,"SKU计数或默认映射异常");
        vo.setPrice(totals.price());vo.setStock(totals.stock());vo.setOrderCount(totals.sales());
        return vo;
    }

    /** 使用 ! 转义，%/_/! 都按用户输入的字面字符匹配。 */
    public static String literalPattern(String term) {
        return term == null ? null : "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
