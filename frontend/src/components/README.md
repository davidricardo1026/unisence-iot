# UI component conventions

Element Plus is the project's base component library. Do not create page-specific
versions of buttons, dialogs, tables, form controls, loading states, or empty
states.

## Source of truth

- Global colour, typography, radius, spacing, control-height, and shadow tokens:
  `src/styles/tailwind.css` under `:root` (`--ui-*`).
- Element Plus theme variables are mapped to those tokens in the same file.
- Global control size is configured once in `src/App.vue`.
- Exact interaction and layout requirements are the live gallery at `/system/ui-kit`
  after login; keep new screens consistent with that kit.
- Run `pnpm audit:ui` (also included in `pnpm lint`) to reject common UI
  convention regressions.

## CRUD composition

Use these shared classes instead of copying scoped CSS:

```vue
<div class="flex min-h-0 flex-1 flex-col overflow-hidden bg-white p-3">
  <el-form class="compact-query-form mb-2 shrink-0" :inline="true" />

  <div class="compact-action-toolbar">
    <div class="flex gap-2"><!-- action buttons --></div>
  </div>

  <div class="compact-table-region">
    <el-table border stripe height="100%" />
  </div>

  <div class="compact-pagination">
    <el-pagination />
  </div>
</div>
```

Form dialogs always use the public classes below. Read-only dialogs should use
`el-descriptions` or tabs and must not imitate an edit form.

```vue
<el-dialog class="compact-edit-dialog" width="480px">
  <el-form class="compact-edit-form" label-width="76px">
    <!-- fields -->
  </el-form>
</el-dialog>
```

## Rules

1. Use semantic Element Plus props (`type="primary"`, `danger`, `link`) rather
   than colour classes or inline styles.
2. Do not hard-code colours, radii, spacing, shadows, or control heights in
   business views. Add a semantic token when a genuinely reusable value is
   missing.
3. Table action columns are fixed right, icon-only, and use tooltips.
4. Every asynchronous screen covers loading, empty, error, and disabled states.
5. New shared patterns belong here or in the global stylesheet; page-scoped CSS
   is reserved for genuinely page-specific layout.
