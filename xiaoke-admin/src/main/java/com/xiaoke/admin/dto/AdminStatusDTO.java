package com.xiaoke.admin.dto;

import jakarta.validation.constraints.*;

public record AdminStatusDTO(@NotNull @Min(0) @Max(1) Integer status) { }
