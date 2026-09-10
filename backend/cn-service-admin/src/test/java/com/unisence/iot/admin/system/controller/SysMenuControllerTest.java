package com.unisence.iot.admin.system.controller;

import com.unisence.iot.admin.system.dto.MenuBatchVisibilityRequest;
import com.unisence.iot.admin.system.dto.MenuCreateRequest;
import com.unisence.iot.admin.system.dto.MenuUpdateRequest;
import com.unisence.iot.admin.system.service.SysMenuService;
import com.unisence.iot.admin.system.vo.MenuTreeVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SysMenuController} Web 层测试。
 * standaloneSetup + mock service，断言查询参数透传、路径 id、以及 {@code @Valid} 校验。
 */
class SysMenuControllerTest {

    private SysMenuService menuService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        menuService = mock(SysMenuService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SysMenuController(menuService)).build();
    }

    @Test
    @DisplayName("GET /system/menus/tree：三个查询参数原样透传，返回树")
    void getMenuTree_passesQueryParams() throws Exception {
        MenuTreeVO node = new MenuTreeVO();
        node.setMenuId(1L);
        node.setMenuName("系统管理");
        when(menuService.getMenuTree(any(), any(), any())).thenReturn(List.of(node));

        mockMvc.perform(get("/system/menus/tree")
                            .param("menuName", "系统")
                            .param("perms", "sys:user")
                            .param("isVisible", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].menuId").value(1))
            .andExpect(jsonPath("$[0].menuName").value("系统管理"));

        verify(menuService).getMenuTree("系统", "sys:user", 1);
    }

    @Test
    @DisplayName("GET /system/menus/tree：不传参数时三个入参均为 null")
    void getMenuTree_absentParamsAreNull() throws Exception {
        when(menuService.getMenuTree(any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/system/menus/tree"))
            .andExpect(status().isOk());

        verify(menuService).getMenuTree(null, null, null);
    }

    @Test
    @DisplayName("PUT /system/menus/{id}/visibility：路径 id + 请求体透传")
    void updateVisibility_valid_delegates() throws Exception {
        String body = "{\"isVisible\":0,\"version\":2}";

        mockMvc.perform(put("/system/menus/5/visibility")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
            .andExpect(status().isOk());

        verify(menuService).updateVisibility(5L, 0, 2);
    }

    @Test
    @DisplayName("PUT /system/menus/{id}/visibility：缺少 version → 400，且不进 service")
    void updateVisibility_missingVersion_badRequest() throws Exception {
        String body = "{\"isVisible\":0}";

        mockMvc.perform(put("/system/menus/5/visibility")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
            .andExpect(status().isBadRequest());

        verify(menuService, never()).updateVisibility(any(), any(), any());
    }

    @Test
    @DisplayName("PUT /system/menus/batch-visibility：可见集合与版本项透传")
    void batchUpdateVisibility_valid_delegates() throws Exception {
        String body = "{\"visibleIds\":[10,11],"
            + "\"items\":[{\"menuId\":10,\"version\":1},{\"menuId\":11,\"version\":1}]}";

        mockMvc.perform(put("/system/menus/batch-visibility")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
            .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MenuBatchVisibilityRequest.MenuVersionItem>> itemsCaptor =
            ArgumentCaptor.forClass(List.class);
        verify(menuService).batchUpdateVisibility(idsCaptor.capture(), itemsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactly(10L, 11L);
        assertThat(itemsCaptor.getValue()).hasSize(2);
    }

    @Test
    @DisplayName("PUT /system/menus/batch-visibility：visibleIds 为空 → 400，且不进 service")
    void batchUpdateVisibility_emptyVisibleIds_badRequest() throws Exception {
        String body = "{\"visibleIds\":[],\"items\":[{\"menuId\":10,\"version\":1}]}";

        mockMvc.perform(put("/system/menus/batch-visibility")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
            .andExpect(status().isBadRequest());

        verify(menuService, never()).batchUpdateVisibility(any(), any());
    }

    // ---------- 结构 CRUD ----------

    @Test
    @DisplayName("POST /system/menus：请求体反序列化后透传，返回新建主键")
    void createMenu_valid_delegatesAndReturnsId() throws Exception {
        when(menuService.createMenu(any())).thenReturn(100L);
        String body = "{\"parentId\":0,\"menuName\":\"设备管理\",\"menuType\":\"D\",\"path\":\"/device\"}";

        mockMvc.perform(post("/system/menus")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").value(100));

        ArgumentCaptor<MenuCreateRequest> captor = ArgumentCaptor.forClass(MenuCreateRequest.class);
        verify(menuService).createMenu(captor.capture());
        assertThat(captor.getValue().getParentId()).isZero();
        assertThat(captor.getValue().getMenuName()).isEqualTo("设备管理");
        assertThat(captor.getValue().getMenuType()).isEqualTo("D");
        assertThat(captor.getValue().getPath()).isEqualTo("/device");
    }

    @Test
    @DisplayName("POST /system/menus：缺少 menuName → 400，且不进 service")
    void createMenu_missingMenuName_badRequest() throws Exception {
        String body = "{\"parentId\":0,\"menuType\":\"D\",\"path\":\"/device\"}";

        mockMvc.perform(post("/system/menus")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
            .andExpect(status().isBadRequest());

        verify(menuService, never()).createMenu(any());
    }

    @Test
    @DisplayName("POST /system/menus：menuType 非 DMCF → 400，且不进 service")
    void createMenu_illegalMenuType_badRequest() throws Exception {
        String body = "{\"parentId\":0,\"menuName\":\"设备管理\",\"menuType\":\"X\",\"path\":\"/device\"}";

        mockMvc.perform(post("/system/menus")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
            .andExpect(status().isBadRequest());

        verify(menuService, never()).createMenu(any());
    }

    @Test
    @DisplayName("PUT /system/menus/{id}：路径 id + 请求体透传，含乐观锁 version")
    void updateMenu_valid_delegates() throws Exception {
        String body = "{\"parentId\":0,\"menuName\":\"设备管理\",\"menuType\":\"D\","
            + "\"path\":\"/device\",\"version\":3}";

        mockMvc.perform(put("/system/menus/5")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
            .andExpect(status().isOk());

        ArgumentCaptor<MenuUpdateRequest> captor = ArgumentCaptor.forClass(MenuUpdateRequest.class);
        verify(menuService).updateMenu(eq(5L), captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(3);
        assertThat(captor.getValue().getMenuName()).isEqualTo("设备管理");
    }

    @Test
    @DisplayName("PUT /system/menus/{id}：缺少 version → 400，且不进 service")
    void updateMenu_missingVersion_badRequest() throws Exception {
        String body = "{\"parentId\":0,\"menuName\":\"设备管理\",\"menuType\":\"D\",\"path\":\"/device\"}";

        mockMvc.perform(put("/system/menus/5")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
            .andExpect(status().isBadRequest());

        verify(menuService, never()).updateMenu(any(), any());
    }

    @Test
    @DisplayName("DELETE /system/menus/{id}：路径 id 透传")
    void deleteMenu_delegates() throws Exception {
        mockMvc.perform(delete("/system/menus/7"))
            .andExpect(status().isOk());

        verify(menuService).deleteMenu(7L);
    }
}
