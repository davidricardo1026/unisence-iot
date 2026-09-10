package com.unisence.iot.admin.system.controller;

import com.unisence.iot.admin.system.dto.DeptCreateRequest;
import com.unisence.iot.admin.system.dto.DeptUpdateRequest;
import com.unisence.iot.admin.system.service.SysDeptService;
import com.unisence.iot.admin.system.vo.DeptTreeVO;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link SysDeptController} Web 层测试。standaloneSetup + mock service。
 */
class SysDeptControllerTest {

    private SysDeptService deptService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        deptService = mock(SysDeptService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SysDeptController(deptService)).build();
    }

    @Test
    @DisplayName("GET /system/depts/tree：查询参数透传，返回树")
    void getDeptTree_passesQueryParams() throws Exception {
        DeptTreeVO node = new DeptTreeVO();
        node.setDeptId(1L);
        node.setDeptName("总公司");
        when(deptService.getDeptTree(any(), any())).thenReturn(List.of(node));

        mockMvc.perform(get("/system/depts/tree")
                            .param("deptName", "研发")
                            .param("status", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].deptId").value(1))
            .andExpect(jsonPath("$[0].deptName").value("总公司"));

        verify(deptService).getDeptTree(eq("研发"), eq(1));
    }

    @Test
    @DisplayName("POST /system/depts：合法请求透传并返回自增 deptId")
    void createDept_valid_returnsGeneratedId() throws Exception {
        when(deptService.createDept(any(DeptCreateRequest.class))).thenReturn(30L);

        String body = "{\"parentId\":0,\"deptName\":\"研发部\",\"status\":1}";

        mockMvc.perform(post("/system/depts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
            .andExpect(status().isOk())
            .andExpect(content().string("30"));

        ArgumentCaptor<DeptCreateRequest> captor = ArgumentCaptor.forClass(DeptCreateRequest.class);
        verify(deptService).createDept(captor.capture());
        assertThat(captor.getValue().getDeptName()).isEqualTo("研发部");
        assertThat(captor.getValue().getParentId()).isEqualTo(0L);
    }

    @Test
    @DisplayName("POST /system/depts：deptName 为空 → 400，且不进 service")
    void createDept_blankDeptName_badRequest() throws Exception {
        String body = "{\"parentId\":0}";

        mockMvc.perform(post("/system/depts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
            .andExpect(status().isBadRequest());

        verify(deptService, never()).createDept(any());
    }

    @Test
    @DisplayName("PUT /system/depts/{id}：路径 id + 请求体透传")
    void updateDept_valid_delegatesWithPathId() throws Exception {
        String body = "{\"parentId\":0,\"deptName\":\"研发部\",\"status\":1,\"version\":0}";

        mockMvc.perform(put("/system/depts/7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
            .andExpect(status().isOk());

        ArgumentCaptor<DeptUpdateRequest> captor = ArgumentCaptor.forClass(DeptUpdateRequest.class);
        verify(deptService).updateDept(eq(7L), captor.capture());
        assertThat(captor.getValue().getDeptName()).isEqualTo("研发部");
        assertThat(captor.getValue().getVersion()).isEqualTo(0);
    }

    @Test
    @DisplayName("PUT /system/depts/{id}：缺少 version → 400，且不进 service")
    void updateDept_missingVersion_badRequest() throws Exception {
        String body = "{\"parentId\":0,\"deptName\":\"研发部\",\"status\":1}";

        mockMvc.perform(put("/system/depts/7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
            .andExpect(status().isBadRequest());

        verify(deptService, never()).updateDept(any(), any());
    }

    @Test
    @DisplayName("DELETE /system/depts/{id}：按路径 id 透传删除")
    void deleteDept_delegatesWithPathId() throws Exception {
        mockMvc.perform(delete("/system/depts/9"))
            .andExpect(status().isOk());

        verify(deptService).deleteDept(9L);
    }
}
