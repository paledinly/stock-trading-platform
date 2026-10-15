/**
 * 왼쪽 메뉴의 화면 목록과 해시 주소(#/closing 같은 주소 뒷부분) 해석 규칙이에요. AppShell과 테스트가 함께 써요.
 */
// 메뉴 목록이에요. group은 왼쪽 메뉴의 묶음 이름이에요(운영: 매일 보는 화면, 탐색·검증: 연구용 화면).
export const ROUTES = [
  { id: 'today', label: '오늘', group: '운영' },
  { id: 'dashboard', label: '대시보드', group: '운영' },
  { id: 'closing', label: '마감 추천', group: '운영' },
  { id: 'wide', label: '시장 스캐너', group: '탐색' },
  { id: 'scanner', label: '실시간 레이더', group: '탐색' },
  { id: 'analytics', label: '성과 분석', group: '검증' },
  { id: 'backtest', label: '백테스트', group: '검증' },
  { id: 'journal', label: '투자기록', group: '기록' },
] as const

export type RouteId = typeof ROUTES[number]['id']
export const GROUPS = ['운영', '탐색', '검증', '기록'] as const

/**
 * 해시 주소에서 화면 이름을 읽어요. 주소가 없거나 모르는 화면(예: 없어진 '장중 추천')이면 '오늘' 화면으로 보내요.
 * 받는 것: location.hash 값. 돌려주는 것: 화면 이름.
 */
export function routeFromHash(hash: string): RouteId {
  const id = hash.replace(/^#\/?/, '').split(/[/?]/)[0]
  return ROUTES.find(route => route.id === id)?.id ?? 'today'
}
