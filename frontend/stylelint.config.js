export default {
    extends: ["stylelint-config-standard"],
    rules: {
        "at-rule-no-unknown": [
            true,
            {
                ignoreAtRules: ["tailwind", "theme", "utility", "variant", "apply", "config"]
            }
        ],
        "no-empty-source": null,
        "import-notation": "string",
        "selector-class-pattern": [
            "^(?:[a-z][a-z0-9]*(?:-[a-z0-9]+)*|el-[a-z0-9_-]+)$",
            {"message": "公共类使用 kebab-case；允许覆盖 Element Plus 的 el-* BEM 类名"}
        ],
        "no-descending-specificity": null
    },
    ignoreFiles: ["dist/**", "node_modules/**", "build/**"]
};
