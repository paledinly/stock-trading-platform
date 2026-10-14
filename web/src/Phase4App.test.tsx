/**
 * 투자기록 화면(JournalPage) 테스트예요. fetch를 빈 목록을 주는 가짜 함수로 바꿔 서버 없이 확인해요.
 * 제목, 새 거래 기록 영역, 종목코드 입력칸이 보이는지 봐요.
 */
import {render,screen} from '@testing-library/react'
import {QueryClient,QueryClientProvider} from '@tanstack/react-query'
import {JournalPage} from './Phase4App'
beforeEach(()=>{globalThis.fetch=vi.fn().mockResolvedValue({ok:true,status:200,json:()=>[]}) as typeof fetch})
test('opens the investment journal and trade editor',()=>{render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><JournalPage/></QueryClientProvider>);expect(screen.getByRole('heading',{name:'투자기록'})).toBeInTheDocument();expect(screen.getByText('새 거래 기록')).toBeInTheDocument();expect(screen.getByLabelText('종목코드')).toBeInTheDocument()})
