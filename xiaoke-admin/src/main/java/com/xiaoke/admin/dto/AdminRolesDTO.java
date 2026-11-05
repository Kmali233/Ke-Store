package com.xiaoke.admin.dto;

import jakarta.validation.constraints.*;
import java.util.List;

/** Full replacement; an empty list deliberately removes all roles. */
public record AdminRolesDTO(@NotNull @Size(max=4) List<@NotNull @Positive Long> roleIds) { }
