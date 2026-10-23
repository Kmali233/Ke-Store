package com.xiaoke.user.service.impl;

import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.common.exception.DatabaseFailures;
import com.xiaoke.common.auth.JwtService;
import com.xiaoke.user.dto.LoginDTO;
import com.xiaoke.user.dto.RegisterDTO;
import com.xiaoke.user.entity.User;
import com.xiaoke.user.mapper.UserMapper;
import com.xiaoke.user.service.UserRegistrationTxService;
import com.xiaoke.user.service.UserService;
import com.xiaoke.user.service.UserState;
import com.xiaoke.user.vo.LoginVO;
import com.xiaoke.user.vo.UserSummaryVO;
import com.xiaoke.user.vo.UserVO;
import jakarta.validation.Validator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.util.Locale;
import java.util.regex.Pattern;

/** 注册流程编排：本类不加事务，写入交给独立的事务组件。 */
@Service
public class UserServiceImpl implements UserService {

    /** 只识别异常末尾的索引名，避免把输入中出现的 uk_username 误认成索引。 */
    private static final Pattern USERNAME_KEY = Pattern.compile(
            "for key ['`](?:[^'`]+\\.)?uk_username['`]\\s*$");

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final UserRegistrationTxService registrationTxService;
    private final Validator validator;
    private final JwtService jwtService;

    public UserServiceImpl(UserMapper userMapper, PasswordEncoder passwordEncoder,
                           UserRegistrationTxService registrationTxService, Validator validator,
                           JwtService jwtService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.registrationTxService = registrationTxService;
        this.validator = validator;
        this.jwtService = jwtService;
    }

    @Override
    public LoginVO login(LoginDTO dto) {
        if (dto == null || !validator.validate(dto).isEmpty()) {
            throw new BusinessException(400, "登录参数不合法");
        }
        User user = userMapper.findUndeleted(dto.getUsername().toLowerCase(Locale.ROOT));
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        // 密码正确后才暴露账号禁用状态。
        UserState.requireActive(user);
        return new LoginVO(jwtService.issue(user.getId(), user.getUsername()), JwtService.EXPIRY_SECONDS,
                new UserSummaryVO(user.getId(), user.getUsername(), user.getNickname()));
    }

    @Override
    public UserVO me(Long userId) {
        User user = requireActive(userId);
        return new UserVO(user.getId(), user.getUsername(), user.getNickname(), user.getPhone(),
                user.getStatus(), user.getCreateTime());
    }

    @Override
    public User requireActive(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(401, "登录身份已失效");
        }
        return UserState.requireActive(userMapper.findByIdIncludingDeleted(userId));
    }


    /** 注册流程，不加事务，写入交给独立的事务组件。
     *
     * 校验注册参数
     * 检查用户名是否存在
     * 计算密码哈希
     * 准备好 User 对象
     *
     * ────────── 开始这笔事务 ──────────
     *
     * 插入用户：userMapper.insert(user)
     * 检查插入行数是否为 1
     *
     * 成功 → 提交
     * 执行过程中失败 → 回滚处理
     *
     * ────────── 这笔事务结束 ──────────
     *
     * 把结果或异常交回 UserServiceImpl
     * */
    @Override
    public void register(RegisterDTO dto) {
        // 保证写入组件返回或抛出异常时，它的事务已经结束。
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Registration must be called without an outer transaction");
        }

        // 校验 DTO
        if (dto == null || !validator.validate(dto).isEmpty()) {
            throw new BusinessException(400, "注册参数不合法");
        }

        // DTO 已 trim；先通过 ASCII 校验，再按固定规则把用户名统一转小写。
        String username = dto.getUsername().toLowerCase(Locale.ROOT);

        // 查询用户名是否占用
        if (userMapper.findIncludingDeleted(username) != null) {
            throw new BusinessException(400, "用户名已存在");
        }

        // 准备 User，设置正常状态、未删除
        User user = new User();
        // 设置用户名
        user.setUsername(username);
        // 设置密码
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        // 设置昵称
        user.setNickname(dto.getNickname());
        // 设置手机号
        user.setPhone(dto.getPhone());
        // 设置状态为正常
        user.setStatus(1);
        // 设置未删除
        user.setDeleted(0);


        // 调用事务组件保存，处理确认的用户名重复异常
        try {
            if (registrationTxService.insertUser(user) != 1) {
                throw new BusinessException(500, "注册保存失败");
            }
        } catch (DuplicateKeyException failure) {
            // 此处位于事务代理之外，回滚已完成；不输出数据库原始错误。
            if (isUsernameConflict(failure)) {
                throw new BusinessException(400, "用户名已存在");
            }
            throw failure;
        }
    }

    private static boolean isUsernameConflict(DuplicateKeyException failure) {
        return DatabaseFailures.causes(failure).stream().anyMatch(cause ->
                cause instanceof SQLException sql
                        && sql.getErrorCode() == 1062
                        && "23000".equals(sql.getSQLState())
                        && sql.getMessage() != null
                        && USERNAME_KEY.matcher(sql.getMessage()).find());
    }
}
