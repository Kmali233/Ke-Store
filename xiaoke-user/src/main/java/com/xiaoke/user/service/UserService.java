package com.xiaoke.user.service;

import com.xiaoke.user.dto.RegisterDTO;
import com.xiaoke.user.dto.LoginDTO;
import com.xiaoke.user.entity.User;
import com.xiaoke.user.vo.LoginVO;
import com.xiaoke.user.vo.UserVO;

/** 用户业务入口，后续按步骤添加登录等方法。 */
public interface UserService {

    /** 注册成功无返回数据，失败通过异常交给上层处理。 */
    void register(RegisterDTO dto);

    LoginVO login(LoginDTO dto);

    UserVO me(Long userId);

    User requireActive(Long userId);
}
