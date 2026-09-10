package com.unisence.iot.admin.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.unisence.iot.admin.entity.SysRoleMenu;
import com.unisence.iot.admin.entity.SysUserRole;
import com.unisence.iot.admin.support.AbstractMysqlIntegrationTest;
import com.unisence.iot.admin.support.MapperItConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 中间表（分配）集成测试（Testcontainers 真 MySQL）—— 覆盖 {@code assignMenus} / {@code assignRoles} 依赖的**真库写路径**。
 * <p>
 * 单测把这两个 Mapper mock 掉了，以下语义**只有真库能验**：
 * <ul>
 *   <li>「按 fk 全删再重插」重建授权：{@code delete(role_id=?)} + 逐条 insert 新集合，最终集合精确替换。</li>
 *   <li>唯一键 {@code (role_id, menu_id)} / {@code (user_id, role_id)} 真拦重复 → {@link DuplicateKeyException}。</li>
 *   <li>C 类中间表**物理删除**（不挂 {@code @TableLogic}）：delete 后行真的消失，而非软删留墓碑。</li>
 * </ul>
 * 中间表无外键约束，故用合成 id（900+）即可，无需 seed 真实 role/menu 行。
 */
@SpringBootTest(classes = MapperItConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class AssignmentMapperIT extends AbstractMysqlIntegrationTest {

    @Autowired
    private SysRoleMenuMapper roleMenuMapper;
    @Autowired
    private SysUserRoleMapper userRoleMapper;

    @Test
    @DisplayName("role_menu 重建：delete(role_id) + 重插，最终授权集合被精确替换")
    void roleMenu_rebuild_replacesOldSet() {
        long roleId = 900L;
        // 初次授权 {910, 911}
        roleMenuMapper.insert(roleMenu(roleId, 910L));
        roleMenuMapper.insert(roleMenu(roleId, 911L));

        // 重建为 {911, 912}：先按 role_id 全删，再插新集合
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, roleId));
        roleMenuMapper.insert(roleMenu(roleId, 911L));
        roleMenuMapper.insert(roleMenu(roleId, 912L));

        List<Long> menuIds = roleMenuMapper.selectList(
                new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, roleId))
            .stream().map(SysRoleMenu::getMenuId).toList();
        assertThat(menuIds).containsExactlyInAnyOrder(911L, 912L); // 910 已被删除
    }

    @Test
    @DisplayName("role_menu 删除是物理删除：delete 后 selectCount=0，不留软删墓碑")
    void roleMenu_delete_isPhysical() {
        long roleId = 920L;
        roleMenuMapper.insert(roleMenu(roleId, 910L));

        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, roleId));

        Long count = roleMenuMapper.selectCount(
            new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, roleId));
        assertThat(count).isZero(); // C 类中间表无 deleted 列，行真被删
    }

    @Test
    @DisplayName("role_menu 唯一键 (role_id, menu_id)：重复插入抛 DuplicateKeyException")
    void roleMenu_uniqueKey_blocksDuplicate() {
        long roleId = 930L;
        roleMenuMapper.insert(roleMenu(roleId, 910L));

        assertThatThrownBy(() -> roleMenuMapper.insert(roleMenu(roleId, 910L)))
            .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("user_role 唯一键 (user_id, role_id)：重复插入抛 DuplicateKeyException")
    void userRole_uniqueKey_blocksDuplicate() {
        long userId = 940L;
        userRoleMapper.insert(userRole(userId, 950L));

        assertThatThrownBy(() -> userRoleMapper.insert(userRole(userId, 950L)))
            .isInstanceOf(DuplicateKeyException.class);
    }

    private static SysRoleMenu roleMenu(Long roleId, Long menuId) {
        SysRoleMenu rm = new SysRoleMenu();
        rm.setRoleId(roleId);
        rm.setMenuId(menuId);
        return rm;
    }

    private static SysUserRole userRole(Long userId, Long roleId) {
        SysUserRole ur = new SysUserRole();
        ur.setUserId(userId);
        ur.setRoleId(roleId);
        return ur;
    }
}
