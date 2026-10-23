package com.xiaoke.user.service;

import com.xiaoke.user.entity.User;
import com.xiaoke.user.mapper.UserMapper;
import com.xiaoke.common.exception.BusinessException;
import org.springframework.stereotype.Service;

/**
 * 注册写入事务：调用方完成校验、查重和密码哈希后，将准备好的用户交给此组件。
 * 注册业务在无事务时调用；业务异常完成回滚后再分类，提交异常保留结果待核对语义。
 */
@Service
public class UserRegistrationTxService {

    private final UserMapper userMapper;
    private final UserTransactions transactions;

    public UserRegistrationTxService(UserMapper userMapper, UserTransactions transactions) {
        this.userMapper = userMapper;
        this.transactions = transactions;
    }

    /** 返回插入行数；异常继续向外抛出，让 Spring 完成事务处理。 */
    public int insertUser(User user) {
        return transactions.execute(() -> {
            // 插入操作
            int rows = userMapper.insert(user);
            if (rows != 1) {
                throw new BusinessException(500, "注册保存失败");
            }
            return rows;
        });
    }
}
