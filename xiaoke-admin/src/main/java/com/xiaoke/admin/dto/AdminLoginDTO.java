package com.xiaoke.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdminLoginDTO {
    @NotNull
    @Pattern(regexp = "[A-Za-z0-9_]{4,50}")
    private String username;

    @NotNull
    @Pattern(regexp = "[\\x20-\\x7E]{12,64}")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    public void setUsername(String username) { this.username = username == null ? null : username.trim(); }
}
