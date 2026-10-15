/**
 * 실시간 레이더 화면(ScannerPage) 테스트예요. fetch를 빈 목록을 주는 가짜 함수로 바꿔 서버 없이 확인해요.
 * 제목, 사용 안내, 후보가 없을 때의 안내 문구가 보이는지 봐요.
 */
import{render,screen}from'@testing-library/react';import{QueryClient,QueryClientProvider}from'@tanstack/react-query';import{ScannerPage}from'./Phase6App';beforeEach(()=>{globalThis.fetch=vi.fn().mockResolvedValue({ok:true,json:async()=>[]})as typeof fetch});test('opens market radar workspace',async()=>{render(<QueryClientProvider client={new QueryClient({defaultOptions:{queries:{retry:false}}})}><ScannerPage/></QueryClientProvider>);expect(screen.getByRole('heading',{name:'실시간 레이더'})).toBeInTheDocument();expect(screen.getByText('사용 안내')).toBeInTheDocument();expect(await screen.findByText('포착된 후보가 없습니다')).toBeInTheDocument()});
