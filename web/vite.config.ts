/**
 * Vite(웹 개발 서버·빌드 도구)와 Vitest(테스트 도구) 설정 파일이에요.
 * 개발 중 /api, /actuator 요청을 로컬 백엔드(8080 포트)로 넘겨 주고, 테스트는 jsdom(브라우저 흉내 환경)에서 돌려요.
 * `npm run dev`, `npm run build`, `npm test`가 이 설정을 읽어요.
 */
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  // 개발 서버 설정: 5173 포트로 열고, 백엔드 API는 프록시(대신 전달)로 연결해요.
  server: {
    port: 5173,
    // ⚠️ 확인 필요: 특정 ngrok(외부 접속 터널) 주소가 하드코딩돼 있어요. 터널 주소가 바뀌면 접속이 막히고, 개인 주소가 저장소에 남는 것으로 보여요.
    allowedHosts: ['enchilada-amulet-unknotted.ngrok-free.dev'],
    proxy: {
      '/api': 'http://localhost:8080',
      '/actuator': 'http://localhost:8080',
    },
  },
  // 테스트 설정: 브라우저 흉내(jsdom), describe/test 전역 사용, 공통 준비 파일 지정이에요.
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.ts',
  },
})
