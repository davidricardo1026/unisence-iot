import fs from 'node:fs'
import path from 'node:path'

const root = path.resolve(import.meta.dirname, '..')
const sourceRoots = [path.join(root, 'src/views'), path.join(root, 'src/components')]
const visualExceptions = new Set([
    'src/views/login/index.vue',
    'src/views/dashboard/index.vue',
    'src/views/welcome/index.vue',
])

function collectVueFiles(directory) {
    return fs.readdirSync(directory, {withFileTypes: true}).flatMap((entry) => {
        const target = path.join(directory, entry.name)
        if (entry.isDirectory()) return collectVueFiles(target)
        return entry.isFile() && entry.name.endsWith('.vue') ? [target] : []
    })
}

const failures = []
const files = sourceRoots.flatMap(collectVueFiles)
const globalStyles = fs.readFileSync(path.join(root, 'src/styles/tailwind.css'), 'utf8')

for (const requiredSnippet of [
    '--el-component-size-small: var(--ui-control-height)',
    '--el-font-size-base: var(--ui-font-xs)',
    '.compact-tabs .el-tabs__item',
    'height: 28px',
    'font-size: var(--ui-font-xs)',
]) {
    if (!globalStyles.includes(requiredSnippet)) {
        failures.push(`src/styles/tailwind.css: 缺少 UI 基线 ${requiredSnippet}`)
    }
}

for (const file of files) {
    const source = fs.readFileSync(file, 'utf8')
    const relative = path.relative(root, file)
    const isCrud = source.includes('compact-query-form') && source.includes('<el-table')
    const isVisualException = visualExceptions.has(relative)

    if (/\.compact-(?:query-form|edit-dialog|edit-form|action-toolbar|table-region|pagination)\s/.test(source)) {
        failures.push(`${relative}: 不得重新定义全局 compact-* 样式`)
    }

    if (/:page-sizes="\s*\[/.test(source)) {
        failures.push(`${relative}: page-sizes 必须使用 PAGE_DEFAULT.PAGE_SIZES`)
    }

    if (!isVisualException && /size=["'](?:default|large)["']/.test(source)) {
        failures.push(`${relative}: 业务控件禁止使用 size=default 或 size=large`)
    }
    if (!isVisualException && /text-\[(?:1[4-9]|[2-9]\d)px\]|text-(?:base|lg|xl|2xl|3xl)/.test(source)) {
        failures.push(`${relative}: 业务页存在未经批准的 ≥14px 字号`)
    }
    if (source.includes('<el-tabs') && !source.includes('compact-tabs')) {
        failures.push(`${relative}: 业务内页签缺少 compact-tabs`)
    }

    if (!isCrud) continue

    if (!source.includes('compact-table-region')) {
        failures.push(`${relative}: CRUD 表格缺少 compact-table-region`)
    }
    if (!source.includes(' border') || !source.includes(' stripe')) {
        failures.push(`${relative}: CRUD 表格必须启用 border 和 stripe`)
    }
    if (source.includes('<el-pagination') && !source.includes('compact-pagination')) {
        failures.push(`${relative}: 分页缺少 compact-pagination`)
    }
    if (source.includes('<el-dialog') && source.includes('compact-edit-form') && !source.includes('compact-edit-dialog')) {
        failures.push(`${relative}: 编辑表单弹窗缺少 compact-edit-dialog`)
    }

    const styleBlock = source.match(/<style scoped>([\s\S]*?)<\/style>/)?.[1] ?? ''
    if (/#[\da-f]{3,8}\b/i.test(styleBlock) || /rgba?\(/i.test(styleBlock)) {
        failures.push(`${relative}: scoped CSS 中存在硬编码颜色，请使用 --ui-* token`)
    }
}

if (failures.length > 0) {
    console.error('UI 规范审计失败:\n')
    for (const failure of failures) console.error(`- ${failure}`)
    process.exitCode = 1
} else {
    console.log(`UI 规范审计通过（${files.length} 个页面/组件）`)
}
