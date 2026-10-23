package com.xiaoke.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 用户实体：对应 t_user 表的一行数据，后续接口使用单独的 VO 返回用户信息。
 */
@Getter
@Setter
@TableName("t_user")
public class User {

    /** 主键由数据库自增生成，插入新用户时不手动填写。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名，注册时按业务规则统一为小写。 */
    private String username;

    /** 只保存 BCrypt 哈希；忽略 JSON 输出，避免意外泄露密码哈希。 */
    @JsonIgnore
    private String password;

    /** 昵称，允许为空。 */
    private String nickname;

    /** 手机号，允许为空。 */
    private String phone;

    /** 账号状态：1 正常，0 禁用。 */
    private Integer status;

    /** 创建时间，对应 create_time，由数据库默认值生成。 */
    private LocalDateTime createTime;

    /** 更新时间，对应 update_time，通常由数据库自动维护。 */
    private LocalDateTime updateTime;

    /** 逻辑删除：0 未删除，1 已删除。 */
    @TableLogic(value = "0", delval = "1")
    private Integer deleted;
}
