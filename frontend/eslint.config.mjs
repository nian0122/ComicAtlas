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
  {
    files: ['src/shared/**/*.{js,mjs,cjs,ts,vue}'],
    rules: {
      // shared 只能向下依赖基础设施，不能反向引用领域、功能或页面层。
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: [
                '@/app/**',
                '@/pages/**',
                '@/widgets/**',
                '@/features/**',
                '@/entities/**',
                '@/views/**',
                '@/components/**',
              ],
              message: 'shared 不得反向依赖业务层。',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['src/entities/**/*.{js,mjs,cjs,ts,vue}'],
    rules: {
      // entities 可以复用 shared 契约，但不能依赖功能实现或页面。
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['@/app/**', '@/pages/**', '@/widgets/**', '@/features/**', '@/views/**', '@/components/**'],
              message: 'entities 不得依赖功能实现或页面层。',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['src/features/**/*.{js,mjs,cjs,ts,vue}'],
    rules: {
      // feature 之间的协作通过各自公开的 API/Store 完成；feature 不得反向依赖页面。
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['@/app/**', '@/pages/**', '@/widgets/**', '@/views/**', '@/components/**'],
              message: 'features 不得依赖上层；页面应组合 feature。',
            },
          ],
        },
      ],
    },
  },
  eslintConfigPrettier,
)
