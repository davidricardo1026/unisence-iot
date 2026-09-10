import {request} from '@/utils/request'
import type {PageRequest, PageResult} from '@/types/api'
import type {MenuItem} from '@/types/menu'

// ==========================================
// 1. 部门管理 (SysDept) API
// ==========================================
export interface DeptTreeVO {
    deptId: number
    parentId: number
    ancestors: string
    deptName: string
    sortOrder: number
    leader: string
    phone: string
    status: number
    version: number
    createTime?: string
    children?: DeptTreeVO[]
}

export interface DeptQueryParams {
    deptName?: string
    status?: number
}

export interface DeptCreateRequest {
    parentId: number
    deptName: string
    sortOrder?: number
    leader?: string
    phone?: string
    status?: number
}

export interface DeptUpdateRequest {
    parentId: number
    deptName: string
    sortOrder: number
    leader?: string
    phone?: string
    status: number
    version: number
}

export function getDeptTree(params?: DeptQueryParams) {
    return request<DeptTreeVO[]>({
        url: '/system/depts/tree',
        method: 'get',
        params,
    })
}

export function createDept(data: DeptCreateRequest) {
    return request<number>({
        url: '/system/depts',
        method: 'post',
        data,
    })
}

export function updateDept(deptId: number, data: DeptUpdateRequest) {
    return request<void>({
        url: `/system/depts/${deptId}`,
        method: 'put',
        data,
    })
}

export function deleteDept(deptId: number) {
    return request<void>({
        url: `/system/depts/${deptId}`,
        method: 'delete',
    })
}

// ==========================================
// 2. 角色管理 (SysRole) API
// ==========================================
export interface RoleVO {
    roleId: number
    roleName: string
    roleCode: string
    status: number
    version: number
    createTime?: string
    menuIds?: number[]
}

export interface RoleQuery {
    roleName?: string
    roleCode?: string
    status?: number
}

export function listRoles(params: PageRequest<RoleQuery>) {
    return request<PageResult<RoleVO>>({
        url: '/system/roles',
        method: 'get',
        params,
    })
}

export function getRole(roleId: number) {
    return request<RoleVO>({
        url: `/system/roles/${roleId}`,
        method: 'get',
    })
}

export interface RoleFormOptionsVO {
    menus: MenuTreeVO[]
}

export function getRoleFormOptions() {
    return request<RoleFormOptionsVO>({url: '/system/roles/form-options', method: 'get'})
}

export interface RoleUpdateRequest {
    roleName: string
    roleCode: string
    status: number
    version: number
    menuIds?: number[]
}

export function createRole(data: Omit<RoleVO, 'roleId'>) {
    return request<number>({
        url: '/system/roles',
        method: 'post',
        data,
    })
}

export function updateRole(roleId: number, data: RoleUpdateRequest) {
    return request<void>({
        url: `/system/roles/${roleId}`,
        method: 'put',
        data,
    })
}

export function deleteRole(roleId: number) {
    return request<void>({
        url: `/system/roles/${roleId}`,
        method: 'delete',
    })
}

// ==========================================
// 2.5 菜单管理 (SysMenu) API
// ==========================================
export interface MenuTreeVO {
    menuId: number
    parentId: number
    menuName: string
    path?: string
    component?: string
    perms?: string
    icon?: string
    sortOrder: number
    isVisible: number
    menuType: 'D' | 'M' | 'C' | 'F'
    moduleId?: number
    version: number
    createTime?: string
    children?: MenuTreeVO[]
}

export interface MenuQueryParams {
    menuName?: string
    perms?: string
    isVisible?: number
}

export interface MenuVisibilityRequest {
    isVisible: number
    version: number
}

export interface MenuBatchVisibilityRequest {
    visibleIds: number[]
    items: { menuId: number; version: number }[]
}

export type MenuType = 'D' | 'M' | 'C' | 'F'

export interface MenuCreateRequest {
    parentId: number
    menuName: string
    menuType: MenuType
    path?: string
    component?: string
    perms?: string
    icon?: string
    sortOrder?: number
    isVisible?: number
}

export interface MenuUpdateRequest extends MenuCreateRequest {
    version: number
}

export function getMenuTree(params?: MenuQueryParams) {
    return request<MenuTreeVO[]>({
        url: '/system/menus/tree',
        method: 'get',
        params,
    })
}

export function createMenu(data: MenuCreateRequest) {
    return request<number>({
        url: '/system/menus',
        method: 'post',
        data,
    })
}

export function updateMenu(menuId: number, data: MenuUpdateRequest) {
    return request<void>({
        url: `/system/menus/${menuId}`,
        method: 'put',
        data,
    })
}

export function deleteMenu(menuId: number) {
    return request<void>({
        url: `/system/menus/${menuId}`,
        method: 'delete',
    })
}

export function updateMenuVisibility(menuId: number, data: MenuVisibilityRequest) {
    return request<void>({
        url: `/system/menus/${menuId}/visibility`,
        method: 'put',
        data,
    })
}

export function batchUpdateMenuVisibility(data: MenuBatchVisibilityRequest) {
    return request<void>({
        url: '/system/menus/batch-visibility',
        method: 'put',
        data,
    })
}

/** 将后端 MenuTreeVO 转为前端 MenuItem */
export function convertToMenuItem(node: MenuTreeVO, parentPath = ''): MenuItem {
    // 拼接绝对路径：相对路径（如 "loginlog"）结合父级路径（如 "/system/log"）
    let fullPath: string | undefined
    if (node.menuType === 'M') {
        fullPath = undefined
    } else if (!node.path) {
        fullPath = undefined
    } else if (node.path.startsWith('/')) {
        fullPath = node.path
    } else {
        fullPath = `${parentPath}/${node.path}`.replace(/\/+/g, '/')
    }
    // M 类型菜单将自身 path 作为子节点的 parentPath
    const childParentPath = node.menuType === 'M' && node.path ? node.path : parentPath
    return {
        id: String(node.menuId),
        title: node.menuName,
        path: fullPath,
        icon: node.icon || undefined,
        // 👑 侧边栏及导航仅展示 目录(M) 和 菜单(C)，自动过滤 按钮(F)
        children: node.children
            ?.filter(child => child.menuType !== 'F')
            .map(child => convertToMenuItem(child, childParentPath)),
    }
}

// ==========================================
// 3. 用户管理 (SysUser) API
// ==========================================
export interface UserVO {
    userId: number
    userCode: string
    userName: string
    phone: string
    status: number
    deptId: number
    deptName: string
    version: number
    createBy: number
    createByName: string
    createTime: string
    roleIds?: number[]
    roleNames?: string[]
    roles?: string[]
    perms?: string[]
    menus?: MenuTreeVO[]
}

export interface UserQuery {
    userCode?: string
    userName?: string
    phone?: string
    status?: number
    deptId?: number
}

export interface UserCreateRequest {
    userCode: string
    userName: string
    phone?: string
    deptId: number
    password?: string
    status?: number
    roleIds?: number[]
}

export interface UserUpdateRequest {
    userName: string
    phone?: string
    deptId: number
    status: number
    version: number
    roleIds?: number[]
}

export interface ResetPasswordRequest {
    password: string
}

export function listUsers(params: PageRequest<UserQuery>) {
    return request<PageResult<UserVO>>({
        url: '/system/users',
        method: 'get',
        params,
    })
}

export interface UserFormOptionsVO {
    departments: DeptTreeVO[];
    roles: RoleVO[]
}

export function getUserFormOptions() {
    return request<UserFormOptionsVO>({url: '/system/users/form-options', method: 'get'})
}

export function createUser(data: UserCreateRequest) {
    return request<number>({
        url: '/system/users',
        method: 'post',
        data,
    })
}

export function updateUser(userId: number, data: UserUpdateRequest) {
    return request<void>({
        url: `/system/users/${userId}`,
        method: 'put',
        data,
    })
}

export function deleteUser(userId: number) {
    return request<void>({
        url: `/system/users/${userId}`,
        method: 'delete',
    })
}

export function resetPassword(userId: number, data: ResetPasswordRequest) {
    return request<void>({
        url: `/system/users/${userId}/reset-password`,
        method: 'put',
        data,
    })
}

/**
 * 获取当前登录用户的最新个人信息及权限
 */
export function getUserProfile() {
    return request<UserVO>({
        url: '/system/users/profile',
        method: 'get',
    })
}

// ==================== DictType 字典类型 ====================

export interface DictTypeVO {
    dictTypeId: number
    dictName: string
    dictType: string
    status: number
    version: number
    createTime: string
}

export interface DictTypeUpdateRequest {
    dictName: string
    dictType: string
    status: number
    version: number
}

export interface DictTypeQuery {
    dictName?: string
    dictType?: string
    status?: number
}

export function listDictTypes(params: PageRequest<DictTypeQuery>) {
    return request<PageResult<DictTypeVO>>({
        url: '/system/dict/types',
        method: 'get',
        params,
    })
}

export function createDictType(data: { dictName: string; dictType: string; status?: number }) {
    return request<number>({
        url: '/system/dict/types',
        method: 'post',
        data,
    })
}

export function updateDictType(dictTypeId: number, data: DictTypeUpdateRequest) {
    return request<void>({
        url: `/system/dict/types/${dictTypeId}`,
        method: 'put',
        data,
    })
}

export function deleteDictType(dictTypeId: number) {
    return request<void>({
        url: `/system/dict/types/${dictTypeId}`,
        method: 'delete',
    })
}

// ==================== DictData 字典数据 ====================

export interface DictDataVO {
    dictDataId: number
    sortOrder: number
    dictLabel: string
    dictValue: string
    dictType: string
    status: number
    version: number
    createTime: string
}

export interface DictDataUpdateRequest {
    dictLabel: string
    dictValue: string
    sortOrder: number
    status: number
    version: number
}

export interface DictDataQuery {
    dictType?: string
    dictLabel?: string
}

export function pageDictData(params: PageRequest<DictDataQuery>) {
    return request<PageResult<DictDataVO>>({
        url: '/system/dict/data',
        method: 'get',
        params,
    })
}

export function createDictData(data: {
    dictType: string
    dictLabel: string
    dictValue: string
    sortOrder?: number
    status?: number
}) {
    return request<number>({
        url: '/system/dict/data',
        method: 'post',
        data,
    })
}

export function updateDictData(dictDataId: number, data: DictDataUpdateRequest) {
    return request<void>({
        url: `/system/dict/data/${dictDataId}`,
        method: 'put',
        data,
    })
}

export function deleteDictData(dictDataId: number) {
    return request<void>({
        url: `/system/dict/data/${dictDataId}`,
        method: 'delete',
    })
}

// ==================== Cache 缓存管理 ====================

export interface CacheRegistryItemVO {
    domain: string
    cacheName: string
    caffeineCacheName: string
    relatedBusiness: string
    supportsScopes: string[]
}

export function getCacheRegistry() {
    return request<CacheRegistryItemVO[]>({
        url: '/system/cache/registry',
        method: 'get',
    })
}

export function clearCache(data: { domain: string; scope: string }) {
    return request<void>({
        url: '/system/cache/clear',
        method: 'post',
        data,
    })
}

// ==================== Metadata 元数据同步 ====================

export type MetadataDomain = 'IOT_PRODUCT' | 'IOT_DEVICE' | 'IOT_THING_MODEL' | 'IOT_RULES'

export interface MetadataInstanceVO {
    schemaVersion: number
    instanceId: string
    state: 'BOOTSTRAPPING' | 'READY' | 'CONVERGING' | 'DEGRADED'
    appliedHead: string
    desiredHead: string
    lastAttemptAt?: number
    lastSuccessAt?: number
    lastBuildDurationMs?: number
    deviceCatalogEntries?: number
    deviceL1EstimatedWeightBytes?: number
    deviceL1HitRate?: number
    deviceL2HitRate?: number
    deviceDbFallbackRate?: number
    consecutiveFailures?: number
    lastErrorCode?: string
    lag?: number
}

export interface MetadataSyncStatusVO {
    committedHead: number
    redisHead?: string
    instances: MetadataInstanceVO[]
}

export function getMetadataSyncStatus() {
    return request<MetadataSyncStatusVO>({
        url: '/system/metadata-sync/status',
        method: 'get',
    })
}

export function rebuildMetadata(data: { metaKey: MetadataDomain; scopeIds?: number[] }) {
    return request<number>({
        url: '/system/metadata-sync/rebuild',
        method: 'post',
        data,
    })
}

export function renotifyMetadata() {
    return request<number>({
        url: '/system/metadata-sync/renotify',
        method: 'post',
    })
}

// ==================== Config 参数配置 ====================

export interface ConfigVO {
    configId: number
    configName: string
    configKey: string
    configValue: string
    configType: number
    version: number
    createTime: string
}

export interface ConfigUpdateRequest {
    configName: string
    configValue: string
    configType: number
    version: number
}

export interface ConfigQuery {
    configName?: string
    configKey?: string
    configType?: number
}

export function listConfigs(params: PageRequest<ConfigQuery>) {
    return request<PageResult<ConfigVO>>({
        url: '/system/configs',
        method: 'get',
        params,
    })
}

export function createConfig(data: {
    configName: string
    configKey: string
    configValue: string
    configType?: number
}) {
    return request<number>({
        url: '/system/configs',
        method: 'post',
        data,
    })
}

export function updateConfig(configId: number, data: ConfigUpdateRequest) {
    return request<void>({
        url: `/system/configs/${configId}`,
        method: 'put',
        data,
    })
}

export function deleteConfig(configId: number) {
    return request<void>({
        url: `/system/configs/${configId}`,
        method: 'delete',
    })
}

export function clearCacheConfig() {
    return request<void>({
        url: '/system/configs/cache',
        method: 'delete',
    })
}

// ==================== Online 在线用户 ====================

export interface UserOnlineVO {
    tokenId: string
    userCode: string
    userName: string
    ipaddr: string
    loginLocation: string
    browser: string
    os: string
    loginTime: string
}

export interface UserOnlineQuery {
    userCode?: string
    ipaddr?: string
}

export function listOnlineUsers(params: PageRequest<UserOnlineQuery>) {
    return request<PageResult<UserOnlineVO>>({
        url: '/system/online/list',
        method: 'get',
        params,
    })
}

export function kickoutUser(tokenId: string) {
    return request<void>({
        url: `/system/online/${tokenId}`,
        method: 'delete',
    })
}

// ==================== File Management 文件管理 ====================

export interface FileInfoVO {
    fileInfoId: number
    fileName: string
    bucketName: string
    objectName: string
    fileSize: number
    fileSuffix: string
    fileUrl: string
    createBy: number
    createByName: string
    createTime: string
}

export interface FileQuery {
    fileName?: string
    bucketName?: string
    beginTime?: string
    endTime?: string
}

export function listFiles(params: PageRequest<FileQuery>) {
    return request<PageResult<FileInfoVO>>({
        url: '/system/files',
        method: 'get',
        params,
    })
}

export function uploadFile(file: File, folder = 'general') {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('folder', folder)
    return request<FileInfoVO>({
        url: '/system/files',
        method: 'post',
        data: formData,
        headers: {'Content-Type': 'multipart/form-data'},
    })
}

export function deleteFile(fileInfoId: number) {
    return request<void>({
        url: `/system/files/${fileInfoId}`,
        method: 'delete',
    })
}

export function batchDeleteFiles(ids: number[]) {
    return request<void>({
        url: '/system/files',
        method: 'delete',
        data: ids,
    })
}

// ---------- 登录日志 ----------
export interface LoginLogVO {
    loginLogId: number
    userCode: string
    userName: string
    ipaddr: string
    loginLocation: string
    browser: string
    os: string
    status: 0 | 1
    msg: string
    loginTime: string
}

export interface LoginLogQuery {
    userCode?: string
    ipaddr?: string
    status?: number
    beginTime?: string
    endTime?: string
}

export function listLoginLogs(params: PageRequest<LoginLogQuery>) {
    return request<PageResult<LoginLogVO>>({
        url: '/system/login-logs',
        method: 'get',
        params,
    })
}

export function deleteLoginLog(loginLogId: number) {
    return request<void>({
        url: `/system/login-logs/${loginLogId}`,
        method: 'delete',
    })
}

export function batchDeleteLoginLogs(ids: number[]) {
    return request<void>({
        url: '/system/login-logs',
        method: 'delete',
        data: ids,
    })
}

export function cleanLoginLogs() {
    return request<void>({
        url: '/system/login-logs/clean',
        method: 'delete',
    })
}

// ---------- 操作日志 ----------
export interface OperLogVO {
    operLogId: number
    title: string
    businessType: number
    method: string
    requestMethod: string
    operatorCode: string
    userName: string
    operIp: string
    operUrl: string
    operParam: string
    jsonResult: string
    status: 0 | 1
    errorMsg: string
    createTime: string
}

export interface OperLogQuery {
    title?: string
    operatorCode?: string
    businessType?: number
    status?: number
    beginTime?: string
    endTime?: string
}

export function listOperLogs(params: PageRequest<OperLogQuery>) {
    return request<PageResult<OperLogVO>>({
        url: '/system/oper-logs',
        method: 'get',
        params,
    })
}

export function detailOperLog(operLogId: number) {
    return request<OperLogVO>({
        url: `/system/oper-logs/${operLogId}`,
        method: 'get',
    })
}

export function batchDeleteOperLogs(ids: number[]) {
    return request<void>({
        url: '/system/oper-logs',
        method: 'delete',
        data: ids,
    })
}

export function cleanOperLogs() {
    return request<void>({
        url: '/system/oper-logs/clean',
        method: 'delete',
    })
}
