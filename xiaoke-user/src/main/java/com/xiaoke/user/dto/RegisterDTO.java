package com.xiaoke.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 注册请求：只接收用户名、原始密码及可选资料。
 */
@Getter
@Setter
public class RegisterDTO {

    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "[A-Za-z0-9_]{4,50}", message = "用户名须为4至50位英文字母、数字或下划线")
    private String username;

    /** 密码按原值校验，空格也是密码内容；仅允许从请求 JSON 读入。 */
    @NotNull(message = "密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度须为6至20位")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    @Size(max = 50, message = "昵称最多50位")
    private String nickname;

    @Size(max = 20, message = "手机号最多20位")
    private String phone;

    /** 先去除首尾空格再校验；通过校验后，业务层再统一转为小写。 */
    public void setUsername(String username) {
        this.username = username == null ? null : username.trim();
    }

    public void setNickname(String nickname) {
        this.nickname = trimToNull(nickname);
    }

    public void setPhone(String phone) {
        this.phone = trimToNull(phone);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        // 去除首尾空格
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
