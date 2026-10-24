package com.xiaoke.product.service;

import com.xiaoke.product.dto.*;
import com.xiaoke.product.vo.CategoryVO;
import java.util.List;

public interface CategoryService {
    List<CategoryVO> listVisibleCategories();
    void addCategory(CategoryCreateDTO dto);
    void updateCategory(CategoryUpdateDTO dto);
    void deleteCategory(Long id);
}
