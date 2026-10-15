/**
 * 웹 앱의 시작점이에요. HTML의 #root 자리에 React 앱을 그려 넣어요.
 * React Query(서버 데이터를 가져오고 캐시하는 라이브러리)의 공용 캐시를 만들어 모든 화면에 나눠 줘요.
 * 브라우저가 index.html을 열 때 Vite가 이 파일을 가장 먼저 실행해요.
 */
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AppShell } from './AppShell'
import { RealtimeBridge } from './RealtimeBridge'
import './theme.css'
import './styles.css'

// 앱 전체가 함께 쓰는 쿼리 캐시(서버 응답을 잠시 보관하는 저장소)예요.
const queryClient = new QueryClient()

// RealtimeBridge는 실시간 시세를 캐시에 반영하고(화면 없음), AppShell은 왼쪽 메뉴가 있는 전체 화면 껍데기(shell)예요.
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RealtimeBridge />
      <AppShell />
    </QueryClientProvider>
  </StrictMode>,
)
