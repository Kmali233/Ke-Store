package com.xiaoke.product.service;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.product.dto.SkuInput;
import com.xiaoke.product.entity.*;
import com.xiaoke.product.mapper.*;
import com.xiaoke.product.vo.SkuVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static com.xiaoke.product.service.CatalogValidation.oneRow;

@Service
@RequiredArgsConstructor
public class SkuService {
    private final SkuMapper skus;
    private final ProductMapper products;
    private final CatalogTransactions transactions;
    private final CatalogValidation validation;
    private final ObjectMapper json;

    public void createDefault(Product product) {
        var row=new ProductSku();row.setProductId(product.getId());row.setName("默认规格");row.setSpecJson("{}");
        row.setSpecHash(hash("{}"));row.setPrice(product.getPrice());row.setStock(product.getStock());
        row.setSales(product.getOrderCount());row.setStatus(1);row.setIsDefault(1);row.setImage(null);oneRow(skus.insert(row));
    }
    public List<SkuVO> list(Long pid,boolean publicOnly) {
        return skus.list(pid).stream().filter(row->!publicOnly || row.getStatus()==1).map(this::view).toList();
    }
    public SkuMapper.Aggregate aggregate(Long pid) { return skus.aggregate(pid); }
    public ProductSku lockDefault(Long pid) {
        var row=skus.lockDefault(pid);
        if (row==null || row.getDeleted()!=0) throw new BusinessException(500,"默认SKU缺失，请核对迁移");
        counters(row);return row;
    }
    public void updateDefault(ProductSku row,java.math.BigDecimal price) {
        if(price!=null && price.compareTo(row.getPrice())!=0) {row.setPrice(price);oneRow(skus.update(row));}
    }
    public SkuVO create(Long pid,SkuInput dto) {
        check(dto,true);
        return unique(()->transactions.write(()->{
            product(pid);var row=new ProductSku();row.setProductId(pid);row.setName(dto.getName());
            row.setSpecJson(specs(dto.getSpecs()));row.setSpecHash(hash(row.getSpecJson()));row.setPrice(dto.getPrice());
            row.setStock(dto.getStock());row.setSales(0);row.setImage(dto.getImage());row.setStatus(dto.getStatus()==null?1:dto.getStatus());
            row.setIsDefault(0);oneRow(skus.insert(row));return view(row);
        }));
    }
    public void update(Long pid,Long id,SkuInput dto) {
        check(dto,false);validation.id(id);
        unique(()->transactions.write(()->{
            product(pid);var row=owned(pid,id);counters(row);
            if(dto.getStock()!=null && !dto.getStock().equals(row.getStock())) throw new BusinessException(409,"不允许通过资料更新覆盖SKU库存");
            if(dto.getName()!=null) row.setName(dto.getName());
            if(dto.getPrice()!=null) row.setPrice(dto.getPrice());
            if(dto.getImage()!=null) row.setImage(dto.getImage());
            if(dto.getStatus()!=null) row.setStatus(dto.getStatus());
            if(dto.getSpecs()!=null) {
                if(row.getIsDefault()==1) throw new BusinessException(409,"默认SKU规格不可修改");
                row.setSpecJson(specs(dto.getSpecs()));row.setSpecHash(hash(row.getSpecJson()));
            }
            oneRow(skus.update(row));return null;
        }));
    }
    public void delete(Long pid,Long id) {
        validation.id(id);transactions.write(()->{
            product(pid);var row=owned(pid,id);
            if(row.getIsDefault()==1) throw new BusinessException(409,"默认SKU不可删除，可下架");
            oneRow(skus.delete(pid,id));return null;
        });
    }
    private Product product(Long pid) {
        validation.id(pid);var row=products.lockById(pid);
        if(row==null || row.getDeleted()!=0) throw new BusinessException(404,"商品不存在");return row;
    }
    private ProductSku owned(Long pid,Long id) {
        var row=skus.lock(pid,id);
        if(row==null || row.getDeleted()!=0) throw new BusinessException(404,"SKU不存在");return row;
    }
    private void check(SkuInput dto,boolean create) {
        validation.check(dto);
        if(create && (dto.getName()==null || dto.getPrice()==null || dto.getStock()==null || dto.getSpecs()==null))
            throw new BusinessException(400,"SKU名称、价格、初始库存和规格必填");
        if(dto.getSpecs()!=null) specs(dto.getSpecs());
    }
    public String specs(Map<String,String> input) {
        if(input==null || input.isEmpty() || input.size()>5) throw new BusinessException(400,"规格应为1至5组");
        var sorted=new TreeMap<String,String>();
        for(var entry:input.entrySet()) {
            String k=entry.getKey()==null?"":entry.getKey().trim(),v=entry.getValue()==null?"":entry.getValue().trim();
            if(k.isEmpty() || k.length()>30 || v.isEmpty() || v.length()>50
                    || k.chars().anyMatch(Character::isISOControl) || v.chars().anyMatch(Character::isISOControl) || sorted.put(k,v)!=null)
                throw new BusinessException(400,"规格名称或值不合法");
        }
        try {return json.writeValueAsString(sorted);} catch(Exception invalid) {throw new BusinessException(400,"规格格式不合法");}
    }
    private SkuVO view(ProductSku row) {
        try {return new SkuVO(row.getId(),row.getProductId(),row.getName(),json.readValue(row.getSpecJson(),new TypeReference<Map<String,String>>(){}),
            row.getPrice(),row.getStock(),row.getSales(),row.getImage(),row.getStatus(),row.getIsDefault()==1);}
        catch(Exception broken) {throw new BusinessException(500,"SKU规格数据异常");}
    }
    private void counters(ProductSku row) {
        if(row.getStock()==null || row.getStock()<0 || row.getSales()==null || row.getSales()<0) throw new BusinessException(500,"SKU计数异常");
    }
    private <T> T unique(java.util.function.Supplier<T> action) {
        try{return action.get();}catch(DuplicateKeyException conflict){throw new BusinessException(409,"规格组合已存在，删除后也不可复用");}
    }
    public static String hash(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
