package com.xiaoke.user.service.impl;

import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.user.dto.AddressCreateDTO;
import com.xiaoke.user.dto.AddressInput;
import com.xiaoke.user.dto.AddressUpdateDTO;
import com.xiaoke.user.entity.Address;
import com.xiaoke.user.mapper.AddressMapper;
import com.xiaoke.user.mapper.UserMapper;
import com.xiaoke.user.service.*;
import com.xiaoke.user.vo.AddressVO;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;

import java.util.List;

/** 每次写入遵守 用户行锁 → 本人目标地址 → 变更 的顺序。 */
@Service
public class AddressServiceImpl implements AddressService {
    private final AddressMapper addresses;
    private final UserMapper users;
    private final UserService userService;
    private final UserTransactions transactions;
    private final Validator validator;

    public AddressServiceImpl(AddressMapper addresses, UserMapper users, UserService userService,
                              UserTransactions transactions, Validator validator) {
        this.addresses = addresses;
        this.users = users;
        this.userService = userService;
        this.transactions = transactions;
        this.validator = validator;
    }

    @Override
    public AddressVO create(Long userId, AddressCreateDTO dto) {
        validate(dto);
        return transactions.execute(() -> {
            lockActiveUser(userId);
            boolean makeDefault = dto.getIsDefault() != null ? dto.getIsDefault()
                    : addresses.lockActiveIds(userId).isEmpty();
            if (makeDefault) { addresses.clearDefaults(userId, null); }
            Address address = new Address();
            copy(dto, address);
            address.setUserId(userId);
            address.setIsDefault(makeDefault ? 1 : 0);
            address.setDeleted(0);
            if (addresses.insert(address) != 1) {
                throw new BusinessException(500, "地址保存失败");
            }
            return toVO(requireOwned(userId, address.getId()));
        });
    }

    @Override
    public List<AddressVO> list(Long userId) {
        userService.requireActive(userId);
        return addresses.listOwned(userId).stream().map(AddressServiceImpl::toVO).toList();
    }

    @Override
    public AddressVO update(Long userId, AddressUpdateDTO dto) {
        validate(dto);
        return transactions.execute(() -> {
            lockActiveUser(userId);
            Address address = requireOwned(userId, dto.getId());
            if (dto.getIsDefault()) { addresses.clearDefaults(userId, address.getId()); }
            copy(dto, address);
            address.setIsDefault(dto.getIsDefault() ? 1 : 0);
            addresses.updateOwned(address);
            return toVO(requireOwned(userId, address.getId()));
        });
    }

    @Override
    public void delete(Long userId, Long id) {
        validateId(id);
        transactions.execute(() -> {
            lockActiveUser(userId);
            requireOwned(userId, id);
            addresses.deleteOwned(userId, id);
            return null;
        });
    }

    /** 设置默认地址 */
    @Override
    public void setDefault(Long userId, Long id) {
        validateId(id);
        transactions.execute(() -> {
            lockActiveUser(userId);
            requireOwned(userId, id);
            addresses.clearDefaults(userId, id);
            addresses.setDefaultOwned(userId, id);
            return null;
        });
    }

    /** 上锁并检查用户是否有效  */
    private void lockActiveUser(Long userId) {
        if (userId == null || userId <= 0) { throw new BusinessException(401, "登录身份已失效"); }
        UserState.requireActive(users.lockById(userId));
    }

    private Address requireOwned(Long userId, Long id) {
        Address address = addresses.lockOwned(userId, id);
        if (address == null) { throw new BusinessException(404, "地址不存在"); }
        return address;
    }

    private void validate(Object dto) {
        if (dto == null || !validator.validate(dto).isEmpty()) {
            throw new BusinessException(400, "地址参数不合法");
        }
    }

    private static void validateId(Long id) {
        if (id == null || id <= 0) { throw new BusinessException(400, "地址ID不合法"); }
    }

    private static void copy(AddressInput dto, Address address) {
        address.setReceiverName(dto.getReceiverName());
        address.setReceiverPhone(dto.getReceiverPhone());
        address.setProvince(dto.getProvince());
        address.setCity(dto.getCity());
        address.setDistrict(dto.getDistrict());
        address.setDetailAddress(dto.getDetailAddress());
    }

    private static AddressVO toVO(Address a) {
        return new AddressVO(a.getId(), a.getReceiverName(), a.getReceiverPhone(), a.getProvince(),
                a.getCity(), a.getDistrict(), a.getDetailAddress(), a.getIsDefault() == 1,
                a.getCreateTime(), a.getUpdateTime());
    }
}
