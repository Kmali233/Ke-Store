package com.xiaoke.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiaoke.user.entity.User;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户数据库操作接口。
 * BaseMapper 已提供 selectById、insert 等方法，启动类的 MapperScan 会注册此接口。
 */
public interface UserMapper extends BaseMapper<User> {

    /**
     * 注册查重：用户名唯一索引也包含已逻辑删除的记录，因此这里查询所有删除状态。
     * 调用前由业务层完成用户名校验和小写规范化；查不到时返回 null。
     */
    @Select("""
            SELECT id, username, password, nickname, phone, status,
                   create_time, update_time, deleted
            FROM t_user
            WHERE username = #{username}
            """)
    /** @Param("username")就是告诉后面的String username 是使用上面SQL的 #{username} */
    User findIncludingDeleted(@Param("username") String username);

    @Select("SELECT * FROM t_user WHERE username = #{username} AND deleted = 0")
    User findUndeleted(@Param("username") String username);

    @Select("SELECT * FROM t_user WHERE id = #{userId}")
    User findByIdIncludingDeleted(@Param("userId") Long userId);

    /** 所有地址写操作先锁同一用户行，形成数据库级的用户互斥。 */
    @Select("SELECT * FROM t_user WHERE id = #{userId} FOR UPDATE")
    User lockById(@Param("userId") Long userId);
}
