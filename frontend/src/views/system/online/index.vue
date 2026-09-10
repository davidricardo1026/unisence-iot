<script setup lang="ts">
import {ElMessage, ElMessageBox} from 'element-plus'
import {kickoutUser, listOnlineUsers, type UserOnlineQuery, type UserOnlineVO} from '@/api/system'
import {PAGE_DEFAULT, type PageRequest} from '@/types/api'

// ==========================================
// 1. 数据定义与初始化
// ==========================================
const loading = ref(false)
const total = ref(0)
const onlineList = ref<UserOnlineVO[]>([])

// 查询参数
const queryParams = reactive<PageRequest<UserOnlineQuery>>({
  pageNum: PAGE_DEFAULT.PAGE_NUM,
  pageSize: PAGE_DEFAULT.PAGE_SIZE,
  query: {
    userCode: '',
    ipaddr: ''
  }
})

// ==========================================
// 2. 业务逻辑方法
// ==========================================

// 加载在线用户列表
async function fetchOnlineUsers() {
  loading.value = true
  try {
    const res = await listOnlineUsers(queryParams)
    if (res && res.data) {
      onlineList.value = res.data.list || []
      total.value = res.data.total || 0
    }
  } catch (err) {
    console.error('加载在线用户列表失败', err)
  } finally {
    loading.value = false
  }
}

// 查询与重置
function handleQuery() {
  queryParams.pageNum = 1
  fetchOnlineUsers()
}

function handleReset() {
  queryParams.query!.userCode = ''
  queryParams.query!.ipaddr = ''
  queryParams.pageNum = PAGE_DEFAULT.PAGE_NUM
  fetchOnlineUsers()
}

// 分页变化
function handleSizeChange(size: number) {
  queryParams.pageSize = size
  fetchOnlineUsers()
}

function handleCurrentChange(page: number) {
  queryParams.pageNum = page
  fetchOnlineUsers()
}

// 强退用户
function handleKickout(row: UserOnlineVO) {
  ElMessageBox.confirm(`是否确认强退用户「${row.userCode}」？`, '警告', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(async () => {
    try {
      await kickoutUser(row.tokenId)
      ElMessage.success('强退成功')
      fetchOnlineUsers()
    } catch (err: unknown) {
      ElMessage.error(getErrorMessage(err))
    }
  }).catch(() => {
  })
}

// 初始化加载
onMounted(() => {
  fetchOnlineUsers()
})
</script>

<template>
  <div class="flex flex-col flex-1 h-full min-h-0 gap-3 overflow-hidden bg-white p-3">
    <!-- 上：查询与工具栏 -->
    <section class="mb-2 shrink-0">
      <el-form
          :inline="true"
          :model="queryParams"
          class="compact-query-form"
          @submit.prevent="handleQuery"
      >
        <el-form-item label="用户编码">
          <el-input
              v-model="queryParams.query!.userCode"
              placeholder="请输入用户编码"
              size="small"
              clearable
              class="!w-48"
          />
        </el-form-item>
        <el-form-item label="登录地址">
          <el-input
              v-model="queryParams.query!.ipaddr"
              placeholder="请输入登录地址"
              size="small"
              clearable
              class="!w-48"
          />
        </el-form-item>
        <el-form-item>
          <el-button
              v-hasPermi="['sys:online:list']"
              type="primary"
              size="small"
              native-type="submit"
          >
            <el-icon class="mr-1">
              <Search/>
            </el-icon>
            查询
          </el-button>
          <el-button
              v-hasPermi="['sys:online:list']"
              size="small"
              @click="handleReset"
          >
            <el-icon class="mr-1">
              <Refresh/>
            </el-icon>
            重置
          </el-button>
        </el-form-item>
      </el-form>
    </section>

    <!-- 下：表格与分页 -->
    <section class="flex min-h-0 flex-1 flex-col overflow-hidden">
      <div class="compact-table-region">
        <el-table
            v-loading="loading"
            :data="onlineList"
            border
            stripe
            size="small"
            height="100%"
        >
          <el-table-column
              type="index"
              label="序号"
              width="55"
              align="center"
          />
          <el-table-column
              prop="tokenId"
              label="会话编号"
              min-width="250"
              show-overflow-tooltip
          />
          <el-table-column
              prop="userCode"
              label="登录账号"
              min-width="120"
          />
          <el-table-column
              prop="userName"
              label="用户名称"
              min-width="100"
          />
          <el-table-column
              prop="ipaddr"
              label="登录地址"
              min-width="130"
          />
          <el-table-column
              prop="loginLocation"
              label="登录地点"
              min-width="120"
          />
          <el-table-column
              prop="browser"
              label="浏览器"
              min-width="120"
              show-overflow-tooltip
          />
          <el-table-column
              prop="os"
              label="操作系统"
              min-width="100"
          />
          <el-table-column
              prop="loginTime"
              label="登录时间"
              min-width="160"
              align="center"
          />
          <el-table-column
              label="操作"
              width="100"
              fixed="right"
              align="center"
          >
            <template #default="{ row }">
              <el-tooltip
                  content="强退"
                  placement="top"
                  :enterable="false"
              >
                <el-button
                    v-hasPermi="['sys:online:kickout']"
                    type="danger"
                    link
                    size="small"
                    @click="handleKickout(row)"
                >
                  <el-icon :size="14">
                    <Delete/>
                  </el-icon>
                  强退
                </el-button>
              </el-tooltip>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="compact-pagination">
        <el-pagination
            v-model:current-page="queryParams.pageNum"
            v-model:page-size="queryParams.pageSize"
            :total="total"
            :page-sizes="PAGE_DEFAULT.PAGE_SIZES"
            size="small"
            layout="total, sizes, prev, pager, next, jumper"
            @size-change="handleSizeChange"
            @current-change="handleCurrentChange"
        />
      </div>
    </section>
  </div>
</template>
