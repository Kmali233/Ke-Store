package com.xiaoke.order.adapter.user;
import org.apache.ibatis.annotations.*;
public interface OrderUserReadMapper {
    @Select("SELECT id,status,deleted FROM t_user WHERE id=#{uid}") OrderUserRow findById(@Param("uid") Long uid);
    @Select("SELECT id,status,deleted FROM t_user WHERE id=#{uid} FOR UPDATE") OrderUserRow lockById(@Param("uid") Long uid);
}
