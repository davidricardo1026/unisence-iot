import fs from "node:fs";
import path from "node:path";
import {fileURLToPath} from "node:url";
import eslint from "@eslint/js";
import tseslint from "typescript-eslint";
import pluginVue from "eslint-plugin-vue";
import globals from "globals";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const autoImports = JSON.parse(
    fs.readFileSync(path.resolve(__dirname, "./.eslintrc-auto-import.json"), "utf-8")
);

export default tseslint.config(
    eslint.configs.recommended,
    ...tseslint.configs.recommended,
    ...pluginVue.configs["flat/recommended"],
    {
        languageOptions: {
            globals: {
                ...globals.browser,
                ...globals.node,
                ...autoImports.globals
            }
        }
    },
    {
        files: ["**/*.ts", "**/*.vue"],
        languageOptions: {
            parserOptions: {
                parser: tseslint.parser,
                extraFileExtensions: [".vue"],
                sourceType: "module"
            }
        },
        rules: {
            "vue/multi-word-component-names": "off",
            "@typescript-eslint/no-explicit-any": "error" // 契约铁律：禁止 any
        }
    },
    {
        ignores: ["dist/**", "node_modules/**", ".vite/**", "build/**"]
    }
);
