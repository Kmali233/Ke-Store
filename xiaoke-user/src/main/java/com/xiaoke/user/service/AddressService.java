package com.xiaoke.user.service;

import com.xiaoke.user.dto.AddressCreateDTO;
import com.xiaoke.user.dto.AddressUpdateDTO;
import com.xiaoke.user.vo.AddressVO;

import java.util.List;

public interface AddressService {
    AddressVO create(Long userId, AddressCreateDTO dto);
    List<AddressVO> list(Long userId);
    AddressVO update(Long userId, AddressUpdateDTO dto);
    void delete(Long userId, Long id);
    void setDefault(Long userId, Long id);
}
