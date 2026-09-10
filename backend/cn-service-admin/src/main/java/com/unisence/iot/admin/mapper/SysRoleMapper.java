package com.unisence.iot.admin.mapper;

import com.unisence.iot.admin.entity.SysRole;
import com.unisence.iot.admin.mapper.projection.UserRoleAssignmentRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysRoleMapper extends AppBaseMapper<SysRole> {

    @Select("""
        SELECT r.role_code
        FROM us_sys_role r
        INNER JOIN us_sys_user_role ur ON r.role_id = ur.role_id
        WHERE ur.user_id = #{userId}
          AND r.status = 1
          AND r.deleted = 0
        """)
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);

    @Select("""
        <script>
        SELECT ur.user_id AS userId, ur.role_id AS roleId, r.role_name AS roleName
        FROM us_sys_user_role ur
        INNER JOIN us_sys_role r ON r.role_id = ur.role_id
        WHERE r.deleted = 0
          AND ur.user_id IN
          <foreach collection="userIds" item="userId" open="(" separator="," close=")">
            #{userId}
          </foreach>
        ORDER BY ur.user_id, r.role_id
        </script>
        """)
    List<UserRoleAssignmentRow> selectRoleAssignmentsByUserIds(@Param("userIds") List<Long> userIds);
}
