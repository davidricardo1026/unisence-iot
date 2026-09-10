import type {Component} from 'vue'
import * as ElementPlusIcons from '@element-plus/icons-vue'
import {
    AlarmClock,
    Bell,
    Bicycle,
    Box,
    Calendar,
    Camera,
    Cellphone,
    Clock,
    Cloudy,
    Coin,
    Compass,
    Connection,
    Coordinate,
    Cpu,
    DataAnalysis,
    DataBoard,
    DataLine,
    Drizzling,
    Goods,
    Grid,
    Guide,
    Histogram,
    HotWater,
    Iphone,
    Key,
    Lightning,
    Link,
    Lock,
    Magnet,
    MapLocation,
    Monitor,
    MoonNight,
    MostlyCloudy,
    Mouse,
    Notification,
    Odometer,
    OfficeBuilding,
    Operation,
    PartlyCloudy,
    Phone,
    PieChart,
    Platform,
    Position,
    Printer,
    ReadingLamp,
    Refrigerator,
    Service,
    SetUp,
    Share,
    Ship,
    Sunny,
    Switch,
    Timer,
    Tools,
    TrendCharts,
    Van,
    VideoCamera,
    View,
    Warning,
    Watch,
    WindPower
} from '@element-plus/icons-vue'

export type ProductIconOption = {
    key: string
    label: string
    group: '设备与计算' | '网络与连接' | '环境与传感' | '安防与视频' | '工业与设施' | '通用' | '其他图标'
    component: Component
}

/** 标准产品库专用的 IoT 图标白名单；持久化值为 key。 */
const curatedProductIconOptions: ProductIconOption[] = [
    {key: 'Cpu', label: '芯片', group: '设备与计算', component: Cpu},
    {key: 'Monitor', label: '终端', group: '设备与计算', component: Monitor},
    {key: 'DataBoard', label: '数据面板', group: '设备与计算', component: DataBoard},
    {key: 'Grid', label: '模块', group: '设备与计算', component: Grid},
    {key: 'Cellphone', label: '手机终端', group: '设备与计算', component: Cellphone},
    {key: 'Iphone', label: '移动设备', group: '设备与计算', component: Iphone},
    {key: 'Mouse', label: '交互终端', group: '设备与计算', component: Mouse},
    {key: 'Printer', label: '打印设备', group: '设备与计算', component: Printer},
    {key: 'Watch', label: '穿戴设备', group: '设备与计算', component: Watch},
    {key: 'Switch', label: '开关设备', group: '设备与计算', component: Switch},
    {key: 'Operation', label: '执行设备', group: '设备与计算', component: Operation},
    {key: 'Connection', label: '连接', group: '网络与连接', component: Connection},
    {key: 'Guide', label: '网关', group: '网络与连接', component: Guide},
    {key: 'Position', label: '定位', group: '网络与连接', component: Position},
    {key: 'Compass', label: '导航', group: '网络与连接', component: Compass},
    {key: 'Link', label: '链路', group: '网络与连接', component: Link},
    {key: 'Share', label: '共享', group: '网络与连接', component: Share},
    {key: 'Coordinate', label: '坐标', group: '网络与连接', component: Coordinate},
    {key: 'MapLocation', label: '地图位置', group: '网络与连接', component: MapLocation},
    {key: 'Phone', label: '电话通信', group: '网络与连接', component: Phone},
    {key: 'Sunny', label: '光照', group: '环境与传感', component: Sunny},
    {key: 'Cloudy', label: '环境', group: '环境与传感', component: Cloudy},
    {key: 'MostlyCloudy', label: '气象', group: '环境与传感', component: MostlyCloudy},
    {key: 'PartlyCloudy', label: '云量', group: '环境与传感', component: PartlyCloudy},
    {key: 'Drizzling', label: '降雨', group: '环境与传感', component: Drizzling},
    {key: 'Lightning', label: '电力', group: '环境与传感', component: Lightning},
    {key: 'WindPower', label: '风电', group: '环境与传感', component: WindPower},
    {key: 'HotWater', label: '热水', group: '环境与传感', component: HotWater},
    {key: 'MoonNight', label: '夜间', group: '环境与传感', component: MoonNight},
    {key: 'Odometer', label: '仪表', group: '环境与传感', component: Odometer},
    {key: 'Histogram', label: '监测', group: '环境与传感', component: Histogram},
    {key: 'Camera', label: '摄像', group: '安防与视频', component: Camera},
    {key: 'VideoCamera', label: '视频', group: '安防与视频', component: VideoCamera},
    {key: 'Bell', label: '告警', group: '安防与视频', component: Bell},
    {key: 'Lock', label: '门禁', group: '安防与视频', component: Lock},
    {key: 'Key', label: '密钥', group: '安防与视频', component: Key},
    {key: 'View', label: '可视化', group: '安防与视频', component: View},
    {key: 'Notification', label: '通知', group: '安防与视频', component: Notification},
    {key: 'AlarmClock', label: '定时告警', group: '安防与视频', component: AlarmClock},
    {key: 'OfficeBuilding', label: '楼宇', group: '工业与设施', component: OfficeBuilding},
    {key: 'Platform', label: '工厂', group: '工业与设施', component: Platform},
    {key: 'Tools', label: '工具', group: '工业与设施', component: Tools},
    {key: 'Box', label: '箱体', group: '工业与设施', component: Box},
    {key: 'Van', label: '车辆', group: '工业与设施', component: Van},
    {key: 'Ship', label: '船舶', group: '工业与设施', component: Ship},
    {key: 'Bicycle', label: '两轮车', group: '工业与设施', component: Bicycle},
    {key: 'Goods', label: '货物', group: '工业与设施', component: Goods},
    {key: 'Magnet', label: '磁性设备', group: '工业与设施', component: Magnet},
    {key: 'Refrigerator', label: '制冷', group: '工业与设施', component: Refrigerator},
    {key: 'ReadingLamp', label: '照明', group: '工业与设施', component: ReadingLamp},
    {key: 'SetUp', label: '配置', group: '通用', component: SetUp},
    {key: 'Timer', label: '定时', group: '通用', component: Timer},
    {key: 'Clock', label: '时钟', group: '通用', component: Clock},
    {key: 'Calendar', label: '日程', group: '通用', component: Calendar},
    {key: 'DataAnalysis', label: '数据分析', group: '通用', component: DataAnalysis},
    {key: 'DataLine', label: '趋势曲线', group: '通用', component: DataLine},
    {key: 'TrendCharts', label: '趋势图表', group: '通用', component: TrendCharts},
    {key: 'PieChart', label: '统计图表', group: '通用', component: PieChart},
    {key: 'Service', label: '服务', group: '通用', component: Service},
    {key: 'Coin', label: '计量', group: '通用', component: Coin},
    {key: 'Warning', label: '警示', group: '通用', component: Warning}
]

/**
 * Element Plus 全量图标开放选择；IoT 常用项保留中文分类，其余图标可通过组件名搜索。
 * 应用入口已经全局注册该图标包，因此这里不会额外引入另一套图标资产。
 */
const curatedIconKeys = new Set(curatedProductIconOptions.map(option => option.key))
const otherProductIconOptions: ProductIconOption[] = Object.entries(ElementPlusIcons)
    .filter(([key]) => !curatedIconKeys.has(key))
    .map(([key, component]) => ({
        key,
        label: key.replace(/([a-z])([A-Z])/g, '$1 $2'),
        group: '其他图标' as const,
        component: component as Component
    }))

export const productIconOptions = [...curatedProductIconOptions, ...otherProductIconOptions]

const productIconRegistry = Object.fromEntries(productIconOptions.map(option => [option.key, option.component])) as Record<string, Component>

/** 不认识的历史值也安全降级到默认芯片图标。 */
export function getProductIcon(icon?: string): Component {
    return productIconRegistry[icon || ''] || Cpu
}
