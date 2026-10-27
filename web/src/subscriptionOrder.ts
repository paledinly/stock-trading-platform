/**
 * 대시보드 '실시간 구독' 목록의 종목 한 줄 모양과 정렬 규칙이에요(Phase3App.tsx가 써요).
 */

/** 실시간 구독 중인 종목 한 줄이에요. sources는 왜 구독했는지(정밀, 다음날 관측 등) 목록이에요. */
export type Subscription = {
  stockCode: string; stockName: string | null; market: string | null; sources: string[]
  state: string | null; stateMessage: string | null; stateUpdatedAt: string | null
  precisionScore: number | null; precisionAddedAt: string | null
}
/**
 * 구독 목록 정렬이에요. 다음날 관측(OVERNIGHT) 종목을 먼저, 그다음 최근에 연결(구독 상태가 바뀐)된 종목 순으로 보여 줘요.
 * 연결 시각이 없으면 뒤로 보내고, 같으면 종목코드 순이에요. 받는 것: 구독 목록. 돌려주는 것: 정렬한 새 목록.
 */
export function sortSubscriptions(items: Subscription[]) {
  const overnight = (item: Subscription) => (item.sources.includes('OVERNIGHT') ? 0 : 1)
  const connectedAt = (item: Subscription) => (item.stateUpdatedAt ? Date.parse(item.stateUpdatedAt) : -Infinity)
  return [...items].sort((a, b) => overnight(a) - overnight(b) || connectedAt(b) - connectedAt(a)
    || a.stockCode.localeCompare(b.stockCode))
}

