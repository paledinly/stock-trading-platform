/**
 * 왼쪽 메뉴에 붙이는 작은 선 아이콘이에요. 외부 아이콘 라이브러리 없이 SVG 경로만 써요.
 * 색은 글자색(currentColor)을 따라가서 밝은/어두운 화면과 선택 상태에 저절로 맞춰져요.
 */
import type { RouteId } from './routes'

// 24×24 칸 기준 선 경로예요. 메뉴 이름과 짝지어 둬요.
const PATHS: Record<RouteId, string[]> = {
  today: ['M4 6h16v14H4z', 'M4 10h16', 'M8 3v4', 'M16 3v4', 'M8 14h3'],
  dashboard: ['M4 4h7v7H4z', 'M13 4h7v4h-7z', 'M13 10h7v10h-7z', 'M4 13h7v7H4z'],
  closing: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M12 7v5l3 2'],
  wide: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M3 12h18', 'M12 3c3 3 3 15 0 18', 'M12 3c-3 3-3 15 0 18'],
  scanner: ['M3 12h4l3-7 4 14 3-7h4'],
  analytics: ['M4 20V10', 'M10 20V4', 'M16 20v-7', 'M22 20H2'],
  backtest: ['M4 12a8 8 0 1 0 2.3-5.6', 'M4 4v4h4', 'M12 8v4l2.5 1.5'],
  journal: ['M5 4h11a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3z', 'M5 17a3 3 0 0 1 3-3h11', 'M9 8h6'],
}

/** 메뉴 아이콘이에요. 받는 것(props): name(화면 이름). 돌려주는 것: 18px SVG(읽기 프로그램에는 숨김). */
export function NavIcon({ name }: { name: RouteId }) {
  return (
    <svg className="navIcon" viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" focusable="false"
      fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      {PATHS[name].map(path => <path key={path} d={path} />)}
    </svg>
  )
}
