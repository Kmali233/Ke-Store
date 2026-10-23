package com.xiaoke.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiaoke.user.entity.Address;
import org.apache.ibatis.annotations.*;

import java.util.List;

/** 手写 SQL 显式带 owner/deleted 条件，不能依赖自动逻辑删除替代归属检查。 */
public interface AddressMapper extends BaseMapper<Address> {
    @Select("""
            SELECT * FROM t_address WHERE user_id = #{userId} AND deleted = 0
            ORDER BY is_default DESC, create_time DESC, id DESC
            """)
    List<Address> listOwned(@Param("userId") Long userId);

    @Select("SELECT * FROM t_address WHERE id = #{id} AND user_id = #{userId} AND deleted = 0 FOR UPDATE")
    Address lockOwned(@Param("userId") Long userId, @Param("id") Long id);

    @Select("SELECT id FROM t_address WHERE user_id = #{userId} AND deleted = 0 FOR UPDATE")
    List<Long> lockActiveIds(@Param("userId") Long userId);

    @Update("""
            <script>
            UPDATE t_address SET is_default = 0, update_time = CURRENT_TIMESTAMP
            WHERE user_id = #{userId} AND deleted = 0 AND is_default = 1
            <if test="exceptId != null">AND id != #{exceptId}</if>
            </script>
            """)
    int clearDefaults(@Param("userId") Long userId, @Param("exceptId") Long exceptId);

    /** PUT 全量替换：省市区为 null 时也必须写回 null。 */
    @Update("""
            UPDATE t_address SET receiver_name = #{address.receiverName}, receiver_phone = #{address.receiverPhone},
            province = #{address.province}, city = #{address.city}, district = #{address.district},
            detail_address = #{address.detailAddress}, is_default = #{address.isDefault}, update_time = CURRENT_TIMESTAMP
            WHERE id = #{address.id} AND user_id = #{address.userId} AND deleted = 0
            """)
    int updateOwned(@Param("address") Address address);

    @Update("""
            UPDATE t_address SET deleted = 1, is_default = 0, update_time = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND deleted = 0
            """)
    int deleteOwned(@Param("userId") Long userId, @Param("id") Long id);

    @Update("""
            UPDATE t_address SET is_default = 1, update_time = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND deleted = 0
            """)
    int setDefaultOwned(@Param("userId") Long userId, @Param("id") Long id);
}
