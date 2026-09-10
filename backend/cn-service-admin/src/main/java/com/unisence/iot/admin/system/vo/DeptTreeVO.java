package com.unisence.iot.admin.system.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DeptTreeVO {
    private Long deptId;
    private Long parentId;
    private String deptName;
    private Integer sortOrder;
    private String leader;
    private String phone;
    private Integer status;
    private Integer version;
    private List<DeptTreeVO> children = new ArrayList<>();
}
