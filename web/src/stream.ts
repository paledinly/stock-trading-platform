/**
 * 서버 실시간 스트림(SSE, /api/v1/stream) 연결을 앱 전체에서 하나만 열어 같이 쓰게 해 주는 모듈이에요.
 * RealtimeBridge(시세·5분봉)와 실시간 레이더(탐지 이벤트)가 각각 연결을 열면 같은 브라우저가 서버 SSE 자리를 두 개 차지해서,
 * 듣는 쪽이 하나라도 있으면 연결을 유지하고 모두 빠지면 닫아요(참조 수 세기).
 */

type Listener = (message: MessageEvent) => void

let source: EventSource | null = null
let users = 0

/**
 * 이벤트 이름별 처리기를 공용 연결에 붙여요.
 * 받는 것: { 이벤트 이름: 처리기 }. 돌려주는 것: 붙인 처리기를 떼고, 마지막 사용자면 연결을 닫는 정리 함수.
 * EventSource가 없는 환경(테스트 등)에서는 아무것도 하지 않아요.
 */
export function subscribeStream(handlers: Record<string, Listener>): () => void {
  if (typeof EventSource === 'undefined') return () => {}
  if (!source) source = new EventSource('/api/v1/stream')
  const current = source
  users++
  Object.entries(handlers).forEach(([name, handler]) => current.addEventListener(name, handler as EventListener))
  return () => {
    Object.entries(handlers).forEach(([name, handler]) => current.removeEventListener(name, handler as EventListener))
    users--
    if (users === 0 && source === current) {
      current.close()
      source = null
    }
  }
}
