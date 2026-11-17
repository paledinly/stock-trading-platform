"""장중 급등 출발 신호 → 매수 → 급등 중 매도 탐색 (2026-11-17). 가설을 찾는 단계이며 판정이 아니다.

정의는 첫 실행 전에 이 머리말에 고정했다.

데이터: intraday_research_bar 5분봉(읽기 전용), 2025-10-30 ~ 2026-11-16. 하루 봉 78개(09:00 ~ 15:25 시작, 인덱스 0 ~ 77).
  봉이 없는 칸은 거래 없음으로 본다(시가=고가=저가=종가=직전 종가, 거래대금 0).
대상(날 d, 종목 i): 보통주(opening.common), 전날 거래대금 10억 이상, 직전 20거래일 중 10일 이상 이력, |갭| <= 30%.
  특수 장 시간인 날(2025-11-13, 2026-01-02)은 신호를 보지 않는다.
신호 봉 k (09:10 ~ 14:30 시작, 인덱스 2 ~ 66). 봉 k가 끝난 뒤 알 수 있는 값만 쓴다.
  VR  = 봉 k 거래대금 / (직전 20거래일 평균 하루 거래대금 / 78)
  R5  = 봉 k 종가 / 봉 k 시가 - 1
  NH  = 봉 k 종가 > 당일 그 전 봉들의 최고가 (신고가)
  DAY = 봉 k 종가 / 전일 종가 - 1 (분석용 구분)
  정의 = VR >= {3, 5, 10} x R5 >= {1%, 2%, 3%} x NH {무관, 필요} = 18개. 종목마다 하루 첫 신호만.
진입: 봉 k+1 시가 E. E >= 전일 종가 x 1.29면 제외(상한가 근처 체결 불가로 본다).
청산 (봉 k+1부터 본다. 한 봉에서 목표와 손절이 모두 닿으면 손절이 먼저라고 본다. 봉 시가가 이미 넘으면 그 시가로 체결):
  TP3SL2  목표 +3%, 손절 -2%, 못 닿으면 종가
  TP5SL3  목표 +5%, 손절 -3%
  TP10SL5 목표 +10%, 손절 -5%
  TRAIL3  손절 = max(E, 진입 후 직전 봉까지 최고가) x 0.97, 닿으면 그 값(시가가 아래면 시가), 아니면 종가
  T30     진입 30분 뒤 봉 시가 (봉 k+7)
  CLOSE   당일 종가
비용: BASE 매수 0.095%, 매도 0.295%.
비교(CTRL): 같은 날 같은 진입 봉에 대상 전체를 샀을 때 같은 청산의 평균. 초과 = 신호 거래 - 그 진입 봉 CTRL.
  목표 먼저 닿은 비율도 CTRL과 비교한다(10-13 실패 분석의 기준).
하루 평균: 그날 신호 거래 평균을 날짜로 평균. 기간 A 2025-11 ~ 2026-04, B 2026-05 ~ 2026-11-16.
후보 조건(보고용): 두 기간 모두 Net > 0, 초과 > 0, 기간마다 거래일 40일 이상.

사용: python -X utf8 trigger.py fetch <env> <cache_dir>    (DB 읽기 전용, 날짜별 npz)
      python -X utf8 trigger.py analyze <cache_dir>
"""
import sys, os, pickle, datetime as dt
import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '2026-11-17-opening-momentum-preregistration'))
from opening import connect, common, SPECIAL, utc  # noqa: E402

NB = 78
BUY, SELL = 0.00095, 0.00295
SPLIT = dt.date(2026, 5, 1)
VRS, R5S, NHS = (3, 5, 10), (0.01, 0.02, 0.03), (False, True)
EXITS = ['TP3SL2', 'TP5SL3', 'TP10SL5', 'TRAIL3', 'T30', 'CLOSE']
TPSL = {'TP3SL2': (0.03, 0.02), 'TP5SL3': (0.05, 0.03), 'TP10SL5': (0.10, 0.05)}


def fetch(envpath, out):
    os.makedirs(out, exist_ok=True)
    c = connect(envpath); cur = c.cursor()
    cur.execute("select id, stock_code, stock_name, is_etf, is_etn from stock")
    stocks = {r[0]: r[1:] for r in cur.fetchall()}
    pickle.dump(stocks, open(os.path.join(out, 'stocks.pkl'), 'wb'))
    cur.execute("select distinct trade_date from intraday_research_state where bars > 0 order by 1")
    days = [r[0] for r in cur.fetchall()]
    for d in days:
        f = os.path.join(out, f'{d}.npz')
        if os.path.exists(f): continue
        cur.execute("select stock_id from intraday_research_state where trade_date = %s and bars > 0", (d,))
        ids = [r[0] for r in cur.fetchall()]
        cur.execute("select stock_id, start_time, open_price, high_price, low_price, close_price, trading_value "
                    "from intraday_research_bar where stock_id = any(%s) and start_time >= %s and start_time < %s",
                    (ids, utc(d, 9, 0), utc(d, 15, 30)))
        rows = cur.fetchall()
        sid = sorted({r[0] for r in rows}); pos = {s: i for i, s in enumerate(sid)}
        a = np.full((len(sid), NB, 5), np.nan, dtype=np.float64)
        for s, t, o, h, l, cl, tv in rows:
            k = int(((t + dt.timedelta(hours=9)).hour * 60 + (t + dt.timedelta(hours=9)).minute - 540) // 5)
            if 0 <= k < NB: a[pos[s], k] = (o, h, l, cl, tv)
        np.savez_compressed(f, sid=np.array(sid), bars=a)
        print(d, len(sid), len(rows), file=sys.stderr, flush=True)


def fill(a):
    """빈 봉을 거래 없음으로 채운다. 첫 봉 전 빈 칸은 첫 시가로."""
    o, h, l, c, tv = (a[..., i].copy() for i in range(5))
    tv = np.where(np.isnan(tv), 0.0, tv)
    last = np.full(c.shape[0], np.nan)
    for k in range(NB):
        miss = np.isnan(c[:, k])
        last = np.where(miss, last, c[:, k])
        for x in (o, h, l, c):
            x[:, k] = np.where(miss, last, x[:, k])
    first = np.array([row[~np.isnan(row)][0] if (~np.isnan(row)).any() else np.nan for row in a[..., 0]])
    for x in (o, h, l, c):
        x[:] = np.where(np.isnan(x), first[:, None], x)
    return o, h, l, c, tv


def net(e, x):
    return x * (1 - SELL) / (e * (1 + BUY)) - 1


def outcomes(o, h, l, c, j):
    """진입 봉 j 시가로 대상 전체를 샀을 때 청산별 Net과 목표 선도달 여부. (N,) 배열 dict."""
    E = o[:, j]; close = c[:, -1]
    H, L, O = h[:, j:], l[:, j:], o[:, j:]
    res, hit = {}, {}
    n = H.shape[1]
    for name, (tp, sl) in TPSL.items():
        T, S = E * (1 + tp), E * (1 - sl)
        th = H >= T[:, None]; sh = L <= S[:, None]
        ti = np.where(th.any(1), th.argmax(1), n); si = np.where(sh.any(1), sh.argmax(1), n)
        idx = np.arange(len(E))
        to = O[idx, np.minimum(ti, n - 1)]; so = O[idx, np.minimum(si, n - 1)]
        px = close.copy()
        win = ti < si; lose = (si <= ti) & (si < n)
        # 같은 봉이면 손절 먼저, 단 그 봉 시가가 이미 목표 이상이면 목표
        same_open_tp = (si == ti) & (si < n) & (to >= T)
        px = np.where(win, np.maximum(T, to), px)
        px = np.where(lose & ~same_open_tp, np.minimum(S, so), px)
        px = np.where(same_open_tp, to, px)
        res[name] = net(E, px); hit[name] = win | same_open_tp
    peak = np.maximum.accumulate(np.concatenate([E[:, None], H[:, :-1]], 1), axis=1)
    peak = np.maximum(peak, E[:, None])
    stop = peak * 0.97
    sh = L <= stop
    si = np.where(sh.any(1), sh.argmax(1), n); idx = np.arange(len(E))
    so = O[idx, np.minimum(si, n - 1)]; sp = stop[idx, np.minimum(si, n - 1)]
    res['TRAIL3'] = net(E, np.where(si < n, np.minimum(sp, so), close))
    res['T30'] = net(E, o[:, j + 6] if j + 6 < NB else close)
    res['CLOSE'] = net(E, close)
    return res, hit


def day_iter(cache):
    stocks = pickle.load(open(os.path.join(cache, 'stocks.pkl'), 'rb'))
    files = sorted(f for f in os.listdir(cache) if f.endswith('.npz'))
    hist = {}  # sid -> list of (day, tvday)
    prev = None
    for f in files:
        d = dt.date.fromisoformat(f[:-4])
        z = np.load(os.path.join(cache, f)); sid = z['sid']; a = z['bars']
        o, h, l, c, tv = fill(a)
        tvday = tv.sum(1)
        today = {int(s): (float(c[i, -1]), float(tvday[i])) for i, s in enumerate(sid)}
        if prev is not None and d not in SPECIAL:
            yield d, stocks, sid, (o, h, l, c, tv), prev, hist
        for i, s in enumerate(sid):
            if d in SPECIAL: continue
            hist.setdefault(int(s), []).append(float(tvday[i]))
            hist[int(s)] = hist[int(s)][-20:]
        prev = today


def analyze(cache):
    # 정의별·청산별: 이벤트 목록 (day, net, excess, hit, ctrl_hit, entry bar, DAY)
    ev = {(v, r, nh): [] for v in VRS for r in R5S for nh in NHS}
    ndays = 0
    for d, stocks, sid, (o, h, l, c, tv), prev, hist in day_iter(cache):
        keep = []
        for i, s in enumerate(sid):
            s = int(s); meta = stocks.get(s)
            if not meta or not common(*meta): continue
            p = prev.get(s); hh = hist.get(s, [])
            if not p or p[1] < 1e9 or len(hh) < 10: continue
            if abs(o[i, 0] / p[0] - 1) > 0.30: continue
            keep.append((i, p[0], sum(hh) / len(hh)))
        if not keep: continue
        ndays += 1
        I = np.array([k[0] for k in keep]); PC = np.array([k[1] for k in keep]); AV = np.array([k[2] for k in keep])
        o2, h2, l2, c2, tv2 = o[I], h[I], l[I], c[I], tv[I]
        vr = tv2 / (AV[:, None] / NB)
        r5 = c2 / o2 - 1
        prevhigh = np.maximum.accumulate(h2, axis=1)
        nh = np.zeros_like(c2, dtype=bool); nh[:, 1:] = c2[:, 1:] > prevhigh[:, :-1]
        dayret = c2 / PC[:, None] - 1
        cache_out = {}
        for j in range(3, 68):  # 진입 봉
            res, hit = outcomes(o2, h2, l2, c2, j)
            ok = o2[:, j] < PC * 1.29
            ctrl = {e: float(np.mean(res[e][ok])) for e in EXITS}
            chit = {e: float(np.mean(hit[e][ok])) for e in TPSL}
            cache_out[j] = (res, hit, ok, ctrl, chit)
        for (v, r, needh), lst in ev.items():
            sig = (vr >= v) & (r5 >= r)
            if needh: sig &= nh
            sig[:, :2] = False; sig[:, 67:] = False
            any_ = sig.any(1); k = sig.argmax(1)
            for m in np.nonzero(any_)[0]:
                j = int(k[m]) + 1
                res, hit, ok, ctrl, chit = cache_out[j]
                if not ok[m]: continue
                lst.append((d, j, float(dayret[m, j - 1]),
                            {e: float(res[e][m]) for e in EXITS}, {e: ctrl[e] for e in EXITS},
                            {e: bool(hit[e][m]) for e in TPSL}, chit))
        print(d, file=sys.stderr, flush=True)
    pickle.dump(ev, open(os.path.join(cache, 'events.pkl'), 'wb'))
    report(ev, ndays)


def dmean(events, f):
    by = {}
    for e in events: by.setdefault(e[0], []).append(f(e))
    if not by: return None, 0
    return sum(sum(v) / len(v) for v in by.values()) / len(by), len(by)


def stats(events):
    out = {}
    for name, flt in (('A', lambda e: e[0] < SPLIT), ('B', lambda e: e[0] >= SPLIT)):
        x = [e for e in events if flt(e)]
        s = {'n': len(x)}
        for ex in EXITS:
            s[ex] = dmean(x, lambda e: e[3][ex])
            s[ex + 'x'] = dmean(x, lambda e: e[3][ex] - e[4][ex])
        for ex in TPSL:
            s[ex + 'h'] = (sum(e[5][ex] for e in x) / len(x), sum(e[6][ex] for e in x) / len(x)) if x else (None, None)
        out[name] = s
    return out


def p(v): return '  -  ' if v is None else f'{v * 100:+.2f}'


def report(ev, ndays):
    print(f'대상 거래일 {ndays}')
    print('\n## 정의별 결과 (하루 평균 Net % A/B, 괄호는 같은 진입 봉 CTRL 대비 초과)\n')
    print('VR | R5 | 신고가 | 거래 A/B | 일수 A/B | ' + ' | '.join(EXITS) + ' | 목표 선도달 TP3/TP5/TP10 (신호 vs CTRL, A/B)')
    cands = []
    for key in ev:
        s = stats(ev[key])
        a, b = s['A'], s['B']
        cells = []
        for ex in EXITS:
            cells.append(f'{p(a[ex][0])}/{p(b[ex][0])} ({p(a[ex + "x"][0])}/{p(b[ex + "x"][0])})')
            if a[ex][0] is not None and b[ex][0] is not None and a[ex][1] >= 40 and b[ex][1] >= 40 and \
                    a[ex][0] > 0 and b[ex][0] > 0 and a[ex + 'x'][0] > 0 and b[ex + 'x'][0] > 0:
                cands.append((key, ex, a[ex][0], b[ex][0]))
        hits = ' '.join(f'{p(a[t + "h"][0])}vs{p(a[t + "h"][1])}/{p(b[t + "h"][0])}vs{p(b[t + "h"][1])}' for t in TPSL)
        print(f'{key[0]} | {key[1] * 100:.0f}% | {"필요" if key[2] else "무관"} | {a["n"]}/{b["n"]} | '
              f'{a["CLOSE"][1]}/{b["CLOSE"][1]} | ' + ' | '.join(cells) + ' | ' + hits)
    print('\n## 후보 (두 기간 Net > 0, 초과 > 0, 기간마다 40일 이상)\n')
    print(f'{len(cands)}개')
    for key, ex, a, b in cands: print(f'  VR>={key[0]} R5>={key[1]:.0%} 신고가 {key[2]} {ex}: A {p(a)} B {p(b)}')
    # 가장 넓은 정의(VR>=3, R5>=1%, 신고가 무관)를 시각·당일 상승폭으로 나눠 본다
    base = ev[(3, 0.01, False)]
    print('\n## 가장 넓은 정의(VR>=3, R5>=1%)를 진입 시각·신호 때 당일 상승폭으로 나눔 (CLOSE / TRAIL3 / TP5SL3 Net A/B)\n')
    groups = {
        '진입 09:15~09:55': lambda e: e[1] <= 11, '진입 10:00~11:55': lambda e: 12 <= e[1] <= 35,
        '진입 12:00~14:35': lambda e: e[1] >= 36,
        '당일 < +5%': lambda e: e[2] < 0.05, '당일 +5~10%': lambda e: 0.05 <= e[2] < 0.10,
        '당일 +10~20%': lambda e: 0.10 <= e[2] < 0.20, '당일 >= +20%': lambda e: e[2] >= 0.20}
    for g, f in groups.items():
        s = stats([e for e in base if f(e)])
        print(f'{g}: 거래 {s["A"]["n"]}/{s["B"]["n"]} | ' + ' | '.join(
            f'{ex} {p(s["A"][ex][0])}/{p(s["B"][ex][0])} (초과 {p(s["A"][ex + "x"][0])}/{p(s["B"][ex + "x"][0])})'
            for ex in ('CLOSE', 'TRAIL3', 'TP5SL3')))


if __name__ == '__main__':
    if sys.argv[1] == 'fetch': fetch(sys.argv[2], sys.argv[3])
    else: analyze(sys.argv[2])
