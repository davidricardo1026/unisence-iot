import type {RouteRecordRaw} from 'vue-router'

export const constantRoutes: RouteRecordRaw[] = [
    {
        path: '/login',
        name: 'Login',
        component: () => import('@/views/login/index.vue'),
        meta: {title: '登录'},
    },
    {
        path: '/',
        component: () => import('@/layouts/index.vue'),
        redirect: '/dashboard',
        children: [
            {
                path: 'dashboard',
                name: 'Dashboard',
                component: () => import('@/views/dashboard/index.vue'),
                meta: {title: '首页', keepAlive: true},
            },
            {
                path: 'welcome',
                name: 'Welcome',
                component: () => import('@/views/welcome/index.vue'),
                meta: {title: '欢迎', keepAlive: true},
            },
            {
                path: 'system-setting/profile',
                name: 'SettingProfile',
                component: () => import('@/views/system-setting/profile/index.vue'),
                meta: {title: '系统参数', keepAlive: true},
            },
            {
                path: 'system/user',
                name: 'SysUser',
                component: () => import('@/views/system/user/index.vue'),
                meta: {title: '用户管理', keepAlive: true},
            },
            {
                path: 'system/role',
                name: 'SysRole',
                component: () => import('@/views/system/role/index.vue'),
                meta: {title: '角色管理', keepAlive: true},
            },
            {
                path: 'system/dept',
                name: 'SysDept',
                component: () => import('@/views/system/dept/index.vue'),
                meta: {title: '部门管理', keepAlive: true},
            },
            {
                path: 'system/menu',
                name: 'SysMenu',
                component: () => import('@/views/system/menu/index.vue'),
                meta: {title: '菜单管理', keepAlive: true},
            },
            {
                path: 'system/dict',
                name: 'SysDict',
                component: () => import('@/views/system/dict/index.vue'),
                meta: {title: '字典管理', keepAlive: true},
            },
            {
                path: 'system/cache',
                name: 'SysCache',
                component: () => import('@/views/system/cache/index.vue'),
                meta: {title: '缓存管理', keepAlive: true},
            },
            {
                path: 'system/config',
                name: 'SysConfig',
                component: () => import('@/views/system/config/index.vue'),
                meta: {title: '参数配置', keepAlive: true},
            },
            {
                path: 'system/online',
                name: 'SysOnline',
                component: () => import('@/views/system/online/index.vue'),
                meta: {title: '在线用户', keepAlive: true},
            },
            {
                path: 'system/log/operlog',
                name: 'SysOperLog',
                component: () => import('@/views/system/operlog/index.vue'),
                meta: {title: '操作日志', keepAlive: true},
            },
            {
                path: 'system/log/loginlog',
                name: 'SysLoginLog',
                component: () => import('@/views/system/loginlog/index.vue'),
                meta: {title: '登录日志', keepAlive: true},
            },
            {
                path: 'system/file',
                name: 'SysFile',
                component: () => import('@/views/system/file/index.vue'),
                meta: {title: '文件管理', keepAlive: true},
            },
            {
                path: 'device/metadata-sync',
                name: 'SysMetadataSync',
                component: () => import('@/views/system-device/metadata-sync/index.vue'),
                meta: {title: '元数据同步', keepAlive: true},
            },
            {
                path: 'system/metadata-sync',
                redirect: '/device/metadata-sync',
            },
            {
                path: 'system/ui-kit',
                name: 'SysUiKit',
                component: () => import('@/views/system/ui-kit/index.vue'),
                meta: {title: 'UI 规范', keepAlive: true},
            },
            {
                path: 'device/standard-product',
                name: 'IotStandardProduct',
                component: () => import('@/views/system-device/standard-product/index.vue'),
                props: {mode: 'standard'},
                meta: {title: '标准产品', keepAlive: true},
            },
            {
                path: 'device/standard-product/new',
                name: 'IotStandardProductCreate',
                component: () => import('@/views/system-device/standard-product/workbench/index.vue'),
                meta: {title: '新建产品', hideInTabs: true},
            },
            {
                path: 'device/standard-product/:productId',
                name: 'IotStandardProductWorkbench',
                component: () => import('@/views/system-device/standard-product/workbench/index.vue'),
                meta: {title: '产品建模', hideInTabs: true},
            },
            {
                path: 'device/product',
                name: 'IotProduct',
                component: () => import('@/views/system-device/standard-product/index.vue'),
                props: {mode: 'product'},
                meta: {title: '产品管理', keepAlive: true},
            },
            {
                path: 'device/product/new',
                name: 'IotProductCreate',
                component: () => import('@/views/system-device/standard-product/workbench/index.vue'),
                props: {mode: 'product'},
                meta: {title: '新建产品', hideInTabs: true},
            },
            {
                path: 'device/product/:productId',
                name: 'IotProductWorkbench',
                component: () => import('@/views/system-device/standard-product/workbench/index.vue'),
                props: {mode: 'product'},
                meta: {title: '产品建模', hideInTabs: true},
            },
            {
                path: 'device/device',
                name: 'IotDevice',
                component: () => import('@/views/system-device/device/index.vue'),
                meta: {title: '设备管理', keepAlive: true},
            },
            {
                path: 'device/device/:deviceId',
                name: 'IotDeviceDetail',
                component: () => import('@/views/system-device/device/detail/index.vue'),
                meta: {title: '设备详情', hideInTabs: true},
            },
            {
                path: 'device/tag',
                name: 'IotTag',
                component: () => import('@/views/system-device/tag/index.vue'),
                meta: {title: '标签管理', keepAlive: true},
            },
            {
                path: 'rule/rules',
                name: 'IotRule',
                component: () => import('@/views/system-rule/rule/index.vue'),
                meta: {title: '规则管理', keepAlive: true},
            },
            {
                path: 'rule/kafka-outputs',
                name: 'IotRuleKafkaOutput',
                component: () => import('@/views/system-rule/kafka-output/index.vue'),
                meta: {title: 'Kafka 输出', keepAlive: true},
            },
            {
                path: 'rule/routes',
                name: 'IotRuleRoute',
                component: () => import('@/views/system-rule/route/index.vue'),
                meta: {title: '透传路由', keepAlive: true},
            },
            // 兼容旧菜单 path（未重新灌种子时 /system-device/* 仍可进）
            {
                path: 'system-device/:rest(.*)*',
                redirect: (to) => {
                    const rest = to.params.rest
                    const suffix = Array.isArray(rest)
                        ? rest.filter(Boolean).join('/')
                        : (rest || '')
                    return {
                        path: suffix ? `/device/${suffix}` : '/device/standard-product',
                        query: to.query,
                    }
                },
            },
        ],
    },
    {
        path: '/:pathMatch(.*)*',
        redirect: '/',
    },
]

/** 动态路由占位：联调后由后端菜单生成并 addRoute */
export const dynamicRoutes: RouteRecordRaw[] = []
