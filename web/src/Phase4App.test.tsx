import {fireEvent,render,screen} from '@testing-library/react'
import {QueryClient,QueryClientProvider} from '@tanstack/react-query'
import {Phase4App} from './Phase4App'
beforeEach(()=>{globalThis.fetch=vi.fn().mockResolvedValue({ok:true,status:200,json:()=>[]}) as typeof fetch})
test('opens the investment journal and trade editor',()=>{render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><Phase4App/></QueryClientProvider>);fireEvent.click(screen.getByRole('button',{name:'투자기록'}));expect(screen.getByRole('heading',{name:'투자기록'})).toBeInTheDocument();expect(screen.getByText('새 거래 기록')).toBeInTheDocument();expect(screen.getByLabelText('종목코드')).toBeInTheDocument()})
