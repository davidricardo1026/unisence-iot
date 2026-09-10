package com.unisence.iot.admin.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DeptUpdateRequest {
    @NotNull(message = "父部门ID不能为空")
    private Long parentId;

    @NotBlank(message = "部门名称不能为空")
    @Size(max = 100, message = "部门名称不能超过100字符")
    private String deptName;

    private Integer sortOrder;
    @Size(max = 50, message = "负责人不能超过50字符")
    private String leader;
    @Size(max = 20, message = "联系电话不能超过20字符")
    private String phone;

    @NotNull(message = "状态不能为空")
    private Integer status;

    @NotNull(message = "版本号不能为空")
    private Integer version;
}
