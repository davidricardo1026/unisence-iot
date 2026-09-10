import type {App} from 'vue'
import {hasPermi} from './permission'

/**
 * 导出全局指令注册函数
 */
export function setupDirectives(app: App) {
    app.directive('hasPermi', hasPermi)
}
