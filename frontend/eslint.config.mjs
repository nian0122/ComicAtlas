import eslint from '@eslint/js'
import eslintConfigPrettier from 'eslint-config-prettier'
import globals from 'globals'
import vue from 'eslint-plugin-vue'
import typescriptEslint from 'typescript-eslint'
import vueParser from 'vue-eslint-parser'

export default typescriptEslint.config(
  {
    ignores: ['dist/**', 'node_modules/**', 'e2e/**', 'e2e-legacy/**', 'test-fixtures/**'],
  },
  eslint.configs.recommended,
  ...typescriptEslint.configs.recommended,
  ...vue.configs['flat/recommended'],
  {
    files: ['**/*.{js,mjs,cjs,ts,vue}'],
    languageOptions: {
      parser: vueParser,
      parserOptions: {
        parser: typescriptEslint.parser,
        extraFileExtensions: ['.vue'],
        sourceType: 'module',
      },
      globals: {
        ...globals.browser,
        ...globals.node,
      },
    },
    rules: {
      'vue/multi-word-component-names': 'off',
      '@typescript-eslint/no-explicit-any': 'off',
      'no-case-declarations': 'off',
      'preserve-caught-error': 'off',
    },
  },
  eslintConfigPrettier,
)
