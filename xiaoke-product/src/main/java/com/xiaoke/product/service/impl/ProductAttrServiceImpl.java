package com.xiaoke.product.service.impl;

import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.common.result.ResultCode;
import com.xiaoke.product.dto.ProductAttrSaveDTO;
import com.xiaoke.product.entity.ProductAttr;
import com.xiaoke.product.mapper.*;
import com.xiaoke.product.service.*;
import com.xiaoke.product.vo.ProductAttrVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Arrays;
import com.xiaoke.audit.*;
import static com.xiaoke.audit.AuditChanges.Field.*;
import static com.xiaoke.product.service.CatalogValidation.oneRow;

@Service
@RequiredArgsConstructor
public class ProductAttrServiceImpl implements ProductAttrService {
    private final ProductMapper products;
    private final ProductAttrMapper attrs;
    private final ProductService productService;
    private final CatalogTransactions transactions;
    private final CatalogValidation validation;
    private final AuditWriter audit;

    @Override
    public List<ProductAttrVO> listAttrs(Long productId) {
        return productService.getProductById(productId).getAttrs();
    }

    @Override
    public void replaceAttrs(Long productId, List<ProductAttrSaveDTO> inputs) {
        validation.id(productId);
        if (inputs == null) { throw new BusinessException(ResultCode.PARAM_ERROR); }
        inputs.forEach(validation::check); // 全数组先检查，含 null 项也会拒绝。
        transactions.write(() -> {
            var product = products.lockById(productId);
            if (product == null || !Integer.valueOf(0).equals(product.getDeleted())) { throw new BusinessException(ResultCode.NOT_FOUND); }
            var before=attrs.selectActiveByProductId(productId).stream().map(r->Arrays.asList(r.getAttrName(),r.getAttrValue(),r.getAttrGroup(),r.getSort())).toList();
            attrs.softDeleteByProductId(productId);
            for (var input : inputs) {
                ProductAttr row = new ProductAttr();
                BeanUtils.copyProperties(input, row);
                row.setProductId(productId);
                row.setSort(input.getSort() == null ? 0 : input.getSort());
                row.setDeleted(0);
                oneRow(attrs.insert(row));
            }
            var after=attrs.selectActiveByProductId(productId).stream().map(r->Arrays.asList(r.getAttrName(),r.getAttrValue(),r.getAttrGroup(),r.getSort())).toList();
            audit.record(AuditAction.PRODUCT_ATTR_REPLACE,productId,AuditChanges.start().put(ATTR_COUNT,before.size(),after.size()).profile(!before.equals(after)));
            return null;
        });
    }
}
