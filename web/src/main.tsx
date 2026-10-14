import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Phase9App } from './Phase9App'
import { RealtimeBridge } from './RealtimeBridge'
import './styles.css'

const queryClient = new QueryClient()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RealtimeBridge />
      <Phase9App />
    </QueryClientProvider>
  </StrictMode>,
)
