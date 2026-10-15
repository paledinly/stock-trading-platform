/**
 * 웹 앱 전체의 껍데기(shell)예요. 왼쪽 메뉴로 화면을 고르고, 오른쪽에 고른 화면을 그려요.
 * 화면 주소는 해시(#/closing 같은 주소 뒷부분)로 남겨서 새로고침·즐겨찾기·뒤로가기가 그대로 동작해요.
 * 밝은/어두운 화면 설정은 localStorage에 기억하고, 저장값이 없으면 운영체제 설정을 따라요.
 * main.tsx가 AppShell을 그려요.
 */
import { useEffect, useState } from 'react'
import { TodayPage } from './TodayPage'
import { Phase3App } from './Phase3App'
import { JournalPage } from './Phase4App'
import { ScannerPage } from './Phase6App'
import { AnalyticsPage } from './Phase7App'
import { MarketWidePage } from './Phase8App'
import { BacktestPage } from './BacktestPage'
import { ClosingRecommendationPage } from './ClosingRecommendationApp'
import { GROUPS, ROUTES, routeFromHash, type RouteId } from './routes'
import { NavIcon } from './NavIcon'
import { SidebarStatus } from './SidebarStatus'
import './appShell.css'

type Theme = 'light' | 'dark'
const THEME_KEY = 'ui.theme'

/**
 * 처음 그릴 테마를 정해요. 저장값 → 운영체제 설정 → 어두운 화면 순서예요.
 * 받는 것: 없음. 돌려주는 것: 'light' 또는 'dark'.
 */
function initialTheme(): Theme {
  try {
    const saved = localStorage.getItem(THEME_KEY)
    if (saved === 'light' || saved === 'dark') return saved
  } catch {
    // Storage may be disabled; fall back to the system preference.
  }
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark'
}

/**
 * 앱 껍데기 컴포넌트예요. 받는 것: 없음. 돌려주는 것: 왼쪽 메뉴와 현재 화면.
 */
export function AppShell() {
  const [route, setRoute] = useState<RouteId>(() => routeFromHash(window.location.hash))
  const [theme, setTheme] = useState<Theme>(initialTheme)

  // 해시 주소가 바뀌면(메뉴 클릭, 뒤로가기) 화면을 바꿔요.
  useEffect(() => {
    const sync = () => setRoute(routeFromHash(window.location.hash))
    window.addEventListener('hashchange', sync)
    return () => window.removeEventListener('hashchange', sync)
  }, [])

  // 테마를 html의 data-theme에 반영하고 기억해요. 차트처럼 CSS 변수를 직접 못 읽는 부품은 'themechange' 이벤트로 알려요.
  useEffect(() => {
    document.documentElement.dataset.theme = theme
    try { localStorage.setItem(THEME_KEY, theme) } catch { /* Storage may be disabled. */ }
    window.dispatchEvent(new Event('themechange'))
  }, [theme])

  /** 메뉴를 눌렀을 때 해시 주소와 화면을 함께 바꿔요. */
  function open(id: RouteId) {
    if (window.location.hash !== `#/${id}`) window.location.hash = `/${id}`
    setRoute(id)
  }

  return (
    <div className="appShell">
      <aside className="appSidebar">
        <div className="appBrand"><i aria-hidden="true">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M4 17l5-6 4 3 7-8" /><path d="M15 6h5v5" /></svg>
        </i><span><b>주식 모니터</b><small>국내주식 · 장마감 전략</small></span></div>
        <nav aria-label="주요 메뉴">
          {GROUPS.map(group => (
            <div className="appNavGroup" key={group}>
              <small>{group}</small>
              {ROUTES.filter(item => item.group === group).map(item => (
                <a key={item.id} href={`#/${item.id}`} aria-current={route === item.id ? 'page' : undefined}
                  onClick={event => { event.preventDefault(); open(item.id) }}><NavIcon name={item.id} /><span>{item.label}</span></a>
              ))}
            </div>
          ))}
        </nav>
        <div className="appSidebarFoot">
          <SidebarStatus />
          <div className="themeSwitch" role="group" aria-label="화면 테마">
            <button type="button" aria-pressed={theme === 'light'} onClick={() => setTheme('light')}>☀ 밝게</button>
            <button type="button" aria-pressed={theme === 'dark'} onClick={() => setTheme('dark')}>☾ 어둡게</button>
          </div>
        </div>
      </aside>
      <div className="appMain">
        {route === 'today' && <TodayPage />}
        {route === 'dashboard' && <Phase3App />}
        {route === 'closing' && <ClosingRecommendationPage advanced />}
        {route === 'wide' && <MarketWidePage />}
        {route === 'scanner' && <ScannerPage />}
        {route === 'analytics' && <AnalyticsPage />}
        {route === 'backtest' && <BacktestPage />}
        {route === 'journal' && <JournalPage />}
      </div>
    </div>
  )
}
