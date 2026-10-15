/**
 * 왼쪽 메뉴 아래의 작은 상태 칸이에요. 지금 장 구간(장 전·정규장·종가 단일가·장 마감)과 실시간 연결 상태,
 * 다음 자동 작업까지 남은 시간을 보여 줘요. 휴장일 여부는 알 수 없어서 주말만 '휴장'으로 보여 주고,
 * 자세한 하루 현황은 '오늘' 화면에서 봐요.
 */
import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { kstClock } from './todayTimeline'

// 평일 하루의 주요 자동 작업 시각이에요(백엔드 예약 시각과 같게 유지해요).
const EVENTS = [
  ['08:59', '실시간 연결'], ['14:50', '정밀 구독 고정'], ['15:15', '공식 평가'], ['15:20', '가상 진입 마감'],
  ['15:40', '전일 후보 관측'], ['15:45', '일별 완결성'], ['18:10', 'DART 수집'], ['18:30', '수정주가 일봉'],
] as const

const minutesOf = (time: string) => Number(time.slice(0, 2)) * 60 + Number(time.slice(3, 5))

/** 지금(한국시간 분)이 장의 어느 구간인지 이름을 붙여요. 주말이면 휴장이에요. */
function sessionLabel(minutes: number, weekday: string) {
  if (weekday === '토' || weekday === '일') return '휴장 (주말)'
  if (minutes < minutesOf('08:30')) return '장 시작 전'
  if (minutes < minutesOf('09:00')) return '장 전 동시호가'
  if (minutes < minutesOf('15:20')) return '정규장'
  if (minutes < minutesOf('15:30')) return '종가 단일가'
  return '장 마감'
}

/** 남은 분을 "1시간 5분" 같은 글자로 바꿔요. */
function duration(minutes: number) {
  const hours = Math.floor(minutes / 60)
  return hours ? `${hours}시간 ${minutes % 60}분` : `${minutes}분`
}

/** 상태 칸 컴포넌트예요. 받는 것: 없음. 돌려주는 것: 장 구간·연결 상태·다음 작업 칸. */
export function SidebarStatus() {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 30_000)
    return () => window.clearInterval(timer)
  }, [])
  const realtime = useQuery({
    queryKey: ['sidebar', 'realtime'],
    queryFn: async () => {
      const response = await fetch('/api/v1/market/realtime/status')
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      return response.json() as Promise<{ realtime?: { connected?: boolean } }>
    },
    refetchInterval: 30_000,
    retry: false,
  })
  const clock = kstClock(now)
  const weekend = clock.weekday === '토' || clock.weekday === '일'
  const next = weekend ? undefined : EVENTS.find(([time]) => minutesOf(time) > clock.minutes)
  const connected = realtime.data?.realtime?.connected
  const live = connected === true ? 'on' : connected === false ? 'off' : 'unknown'
  return (
    <div className="sidebarStatus" aria-label="장 상태">
      <div className="sidebarSession">
        <b>{sessionLabel(clock.minutes, clock.weekday)}</b>
        <time>{clock.time.slice(0, 5)}</time>
      </div>
      <p className={`sidebarLive ${live}`}>
        <i aria-hidden="true" />
        {live === 'on' ? '실시간 연결됨' : live === 'off' ? '실시간 연결 안 됨' : '연결 상태 확인 중'}
      </p>
      {next && <p className="sidebarNext">다음 {next[0]} {next[1]} · {duration(minutesOf(next[0]) - clock.minutes)} 후</p>}
    </div>
  )
}
