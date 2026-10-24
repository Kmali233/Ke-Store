package com.xiaoke.order.adapter.product;
import org.apache.ibatis.annotations.*;
/** 自有适配Mapper，不依赖product jar；恢复SQL有意包含软删和下架商品。 */
public interface OrderProductMapper {
    String COLUMNS="id,category_id,name,image,price,stock,sales,status,deleted";
    @Select("SELECT "+COLUMNS+" FROM t_product WHERE id=#{id}") OrderProductRow findById(@Param("id") Long id);
    @Select("SELECT "+COLUMNS+" FROM t_product WHERE id=#{id} FOR UPDATE") OrderProductRow selectForUpdate(@Param("id") Long id);
    @Update("""
        UPDATE t_product SET stock=stock-#{q},sales=sales+#{q},update_time=CURRENT_TIMESTAMP
        WHERE id=#{id} AND deleted=0 AND status=1 AND stock>=#{q} AND sales<=2147483647-#{q}
        """)
    int deduct(@Param("id") Long id,@Param("q") int quantity);
    @Update("""
        UPDATE t_product SET stock=stock+#{q},sales=sales-#{q},update_time=CURRENT_TIMESTAMP
        WHERE id=#{id} AND stock<=2147483647-#{q} AND sales>=#{q}
        """)
    int restore(@Param("id") Long id,@Param("q") int quantity);
}
