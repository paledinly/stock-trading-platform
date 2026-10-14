/**
 * ESLint(코드 검사 도구) 설정 파일이에요.
 * TypeScript 권장 규칙, React 훅(hook) 규칙, Vite 빠른 새로고침 규칙을 .ts/.tsx 파일에 적용해요.
 * `npx eslint .` 실행 시 이 설정을 읽어요.
 */
import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'

export default tseslint.config([
  // 빌드 결과물(dist)은 검사하지 않아요.
  { ignores: ['dist'] },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      ...tseslint.configs.recommended,
      reactHooks.configs['recommended-latest'],
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
    },
  },
])
