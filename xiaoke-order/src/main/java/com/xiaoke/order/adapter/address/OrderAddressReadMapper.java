package com.xiaoke.order.adapter.address;
import org.apache.ibatis.annotations.*;

// 执行地址查询,本质上是个Mapper
public interface OrderAddressReadMapper {

    /**
     * 根据用户 ID 和地址 ID，查询这个用户拥有、且没有删除的地址，
     * 返回 OrderAddressRow
     * @param uid
     * @param aid
     * @return
     */
    @Select("""
        SELECT receiver_name,receiver_phone,province,city,district,detail_address
        FROM t_address WHERE id=#{aid} AND user_id=#{uid} AND deleted=0
        """)
    OrderAddressRow selectOwned(@Param("uid") Long uid,@Param("aid") Long aid);
}
