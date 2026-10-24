package com.xiaoke.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class ProductAttrSaveDTO {
    @NotBlank @Size(max = 50)
    private String attrName;
    @NotBlank @Size(max = 255)
    private String attrValue;
    @Size(max = 50)
    private String attrGroup;
    private Integer sort;

    public void setAttrName(String value) { attrName = value == null ? null : value.trim(); }
    public void setAttrValue(String value) { attrValue = value == null ? null : value.trim(); }
    public void setAttrGroup(String value) {
        attrGroup = value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
