package com.xiaoke.product.service;

import com.xiaoke.product.dto.*;
import com.xiaoke.product.vo.CategoryVO;
import java.util.List;

public interface CategoryService {
    List<com.xiaoke.product.vo.AdminCategoryVO> listForAdmin();
    List<CategoryVO> listVisibleCategories();
    void addCategory(CategoryCreateDTO dto);
    void updateCategory(CategoryUpdateDTO dto);
    void deleteCategory(Long id);
}
