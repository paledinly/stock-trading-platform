"""장초반 모멘텀 당일 매매 사전 등록 분석 (2026-11-17). 정의는 README.md를 따른다.

실행 전에 고정한 세부 (README가 정하지 않은 것):
- 시각은 KST. DB start_time은 UTC라 09:00 KST = 00:00Z.
- 거래일 목록: intraday_research_state에서 bars > 0인 trade_date. 전날 = 이 목록의 바로 앞 날.
- 전일 종가 = 전날 마지막 봉 종가. 당일 종가(CLOSE 청산) = 당일 마지막 봉 종가. 보통 15:25 시작 봉(종가 단일가)이다.
  2025-11-13처럼 특수 장 시간인 날이 전날이면 그날 마지막 봉이 종가다.
- 전날 거래대금 = 전날 모든 5분봉 거래대금 합.
- 09:00·09:05 봉이 모두 있고 두 봉 거래량 합 > 0이어야 한다.
- RVOL10 이력: 직전 20거래일(특수 장 시간 날 제외) 중 이 종목 VALUE10이 있는 날의 평균. 있는 날이 10일 미만이면 제외.
  수집 명단에 없던 날(거래대금이 작아 수집되지 않은 날)은 평균에서 빠진다.
- S1·S2·CTRL 진입: 09:10~09:25 시작 봉 중 첫 봉의 시가. 없으면 진입하지 않는다.
- S3·S4 신호 봉은 시작 09:10~10:20, 진입 봉(신호 다음 다음 봉 또는 다음 봉)은 시작 ≤ 10:30. 봉은 실제로 있는 봉 순서로 센다.
  S3: 봉 k의 저가 ≤ VWAP10, 바로 다음 봉 k+1의 종가 > VWAP10 → 봉 k+2 시가 진입.
  S4: 종가 > OR_HIGH인 첫 봉 k → 봉 k+1 시가 진입.
  같은 종목은 하루 한 번(첫 신호)만.
- 진입가 ≥ 전일 종가 × 1.29 이면 제외. STOP_OR에서 진입가 ≤ OR_LOW이면 그 거래는 제외(유효 손절 없음).
- T30: 진입 봉 시작 + 30분 이후 첫 봉 시가. 없으면 당일 종가.
- STOP_OR: 진입 봉부터 저가 ≤ OR_LOW인 첫 봉. 그 봉 시가 < OR_LOW면 시가, 아니면 OR_LOW. 없으면 당일 종가.
- 동률: RVOL10 내림차순, 같으면 종목코드 순.
- 하루 평균: 거래한 날만. 거래 없는 날은 날짜 평균에서 빠진다(현금 보유).
- 재표본: 기간 안 거래일 수열에서 연속 5일 묶음을 원형으로 뽑는다(10,000회, seed 20261117).

사용: python opening.py fetch <env> <cache.pkl>   (DB 읽기 전용)
      python opening.py analyze <cache.pkl>
"""
import sys, pickle, re, datetime as dt
from collections import defaultdict

SPECIAL = {dt.date(2025, 11, 13), dt.date(2026, 1, 2)}
EXPLORE = (dt.date(2025, 12, 1), dt.date(2026, 4, 30))
CONFIRM = (dt.date(2026, 5, 1), dt.date(2026, 10, 22))
HOLDOUT = (dt.date(2026, 10, 23), dt.date(2026, 11, 16))
BUY, SELL = 0.00095, 0.00295
TOP = 5

def utc(day, hh, mm):
    return dt.datetime(day.year, day.month, day.day, hh, mm, tzinfo=dt.timezone.utc) - dt.timedelta(hours=9)


def connect(path):
    import psycopg
    env = {}
    for line in open(path, encoding='utf-8'):
        if '=' in line and not line.lstrip().startswith('#'):
            k, v = line.rstrip('\n').split('=', 1); env[k.strip()] = v.strip()
    m = re.match(r'jdbc:postgresql://([^:/]+):(\d+)/([^?]+)', env['DB_URL'])
    c = psycopg.connect(host=m[1], port=int(m[2]), dbname=m[3], user=env['DB_USERNAME'], password=env['DB_PASSWORD'],
                        sslmode='require', connect_timeout=15)
    c.read_only = True
    return c


DAY_SQL = """
with b as (select stock_id, start_time t, open_price o, high_price h, low_price l, close_price c, volume v, trading_value tv
           from intraday_research_bar where stock_id = any(%(ids)s) and start_time >= %(a)s and start_time < %(z)s),
f as (select stock_id,
        max(o) filter (where t=%(t0)s) o0, max(h) filter (where t=%(t0)s) h0, max(l) filter (where t=%(t0)s) l0,
        max(c) filter (where t=%(t0)s) c0, max(v) filter (where t=%(t0)s) v0, max(tv) filter (where t=%(t0)s) tv0,
        max(o) filter (where t=%(t1)s) o1, max(h) filter (where t=%(t1)s) h1, max(l) filter (where t=%(t1)s) l1,
        max(c) filter (where t=%(t1)s) c1, max(v) filter (where t=%(t1)s) v1, max(tv) filter (where t=%(t1)s) tv1,
        sum(tv) tvday
      from b group by stock_id),
lst as (select distinct on (stock_id) stock_id, c cday from b order by stock_id, t desc),
f2 as (select f.*, lst.cday from f join lst using (stock_id)),
e as (select distinct on (stock_id) stock_id, t et, o eo from b where t >= %(t2)s and t <= %(t25)s order by stock_id, t),
x as (select f2.*, e.et, e.eo, least(f2.l0, f2.l1) orl from f2 left join e using (stock_id)),
hit as (select distinct on (b.stock_id) b.stock_id, b.o ho from b join x using (stock_id)
        where x.et is not null and b.t >= x.et and b.l <= x.orl order by b.stock_id, b.t),
t30 as (select distinct on (b.stock_id) b.stock_id, b.o t30o from b join x using (stock_id)
        where x.et is not null and b.t >= x.et + interval '30 minutes' order by b.stock_id, b.t)
select x.stock_id, x.o0, x.h0, x.l0, x.c0, x.v0, x.tv0, x.o1, x.h1, x.l1, x.c1, x.v1, x.tv1, x.tvday, x.cday,
       x.et, x.eo, x.orl, hit.ho, t30.t30o
from x left join hit using (stock_id) left join t30 using (stock_id)
"""
COLS = ['sid', 'o0', 'h0', 'l0', 'c0', 'v0', 'tv0', 'o1', 'h1', 'l1', 'c1', 'v1', 'tv1', 'tvday', 'cday', 'et', 'eo', 'orl', 'ho', 't30o']


def fetch(envpath, out):
    c = connect(envpath); cur = c.cursor()
    cur.execute("select id, stock_code, stock_name, is_etf, is_etn from stock")
    stocks = {r[0]: r[1:] for r in cur.fetchall()}
    cur.execute("select distinct trade_date from intraday_research_state where bars > 0 order by 1")
    days = [r[0] for r in cur.fetchall()]
    data = {'stocks': stocks, 'days': days, 'day': {}, 'pool_bars': {}}
    for d in days:
        cur.execute("select stock_id from intraday_research_state where trade_date = %s and bars > 0", (d,))
        p = dict(ids=[r[0] for r in cur.fetchall()], a=utc(d, 9, 0), z=utc(d, 18, 0),
                 t0=utc(d, 9, 0), t1=utc(d, 9, 5), t2=utc(d, 9, 10), t25=utc(d, 9, 25))
        cur.execute(DAY_SQL, p)
        data['day'][d] = {r[0]: dict(zip(COLS, r)) for r in cur.fetchall()}
        print(d, len(data['day'][d]), file=sys.stderr)
    # S3·S4 후보 풀(S2 조건 통과 종목)의 당일 봉. 풀은 RVOL 이력이 필요해 날짜 순서로 계산한다.
    feats = features(data)
    for d, rows in feats.items():
        sids = [r['sid'] for r in rows if r['s2']]
        if not sids: continue
        cur.execute("select stock_id, start_time, open_price, high_price, low_price, close_price from intraday_research_bar "
                    "where stock_id = any(%s) and start_time >= %s and start_time < %s order by stock_id, start_time",
                    (sids, utc(d, 9, 0), utc(d, 18, 0)))
        bars = defaultdict(list)
        for r in cur.fetchall(): bars[r[0]].append(r[1:])
        data['pool_bars'][d] = dict(bars)
    pickle.dump(data, open(out, 'wb'))


def common(code, name, etf, etn):
    return len(code) == 6 and code.endswith('0') and code[0] not in 'QF' and '스팩' not in (name or '') and not etf and not etn


def features(data):
    """날마다 대상 종목의 09:10 지표와 S1/S2 조건을 계산한다. 결과를 보지 않는 단계다."""
    days = data['days']; out = {}
    hist = defaultdict(dict)  # sid -> day -> VALUE10 (특수일 제외)
    for i, d in enumerate(days):
        today = data['day'][d]
        for sid, r in today.items():
            if r['tv0'] is not None and r['tv1'] is not None and d not in SPECIAL:
                hist[sid][d] = float(r['tv0']) + float(r['tv1'])
        if i == 0 or d in SPECIAL: continue
        prev = data['day'][days[i - 1]]
        window = [x for x in days[max(0, i - 40):i] if x not in SPECIAL][-20:]
        rows = []
        for sid, r in today.items():
            meta = data['stocks'].get(sid)
            if not meta or not common(*meta): continue
            pr = prev.get(sid)
            if not pr or pr['tvday'] is None or float(pr['tvday']) < 1e9 or pr['cday'] is None: continue
            if None in (r['o0'], r['c1'], r['o1'], r['tv0'], r['tv1'], r['v0'], r['v1']): continue
            vol = float(r['v0']) + float(r['v1'])
            if vol <= 0: continue
            pc = float(pr['cday']); o = float(r['o0']); P = float(r['c1'])
            gap = o / pc - 1
            if abs(gap) > 0.30: continue
            past = [hist[sid][x] for x in window if x in hist[sid]]
            if len(past) < 10: continue
            base = sum(past) / len(past)
            v10 = float(r['tv0']) + float(r['tv1'])
            row = dict(sid=sid, code=meta[0], pc=pc, open=o, P=P, gap=gap, ret10=P / pc - 1, vwap=v10 / vol, value10=v10,
                       rvol=v10 / base if base > 0 else None, last5=P / float(r['o1']) - 1,
                       orh=max(float(r['h0']), float(r['h1'])), orl=float(r['orl']), r=r)
            if row['rvol'] is None: continue
            row['s1'] = row['ret10'] >= 0.02 and P > row['vwap'] and P > o and row['rvol'] >= 2
            row['s2'] = row['s1'] and gap <= 0.05 and row['ret10'] <= 0.10 and row['last5'] <= 0.03
            rows.append(row)
        out[d] = rows
    return out


def net(entry, exit_):
    return (exit_ * (1 - SELL)) / (entry * (1 + BUY)) - 1, exit_ / entry - 1


def exits_0910(row):
    """09:10 진입(S1·S2·CTRL)의 세 청산. 진입 불가면 None."""
    r = row['r']
    if r['eo'] is None: return None
    eo = float(r['eo'])
    if eo >= row['pc'] * 1.29: return None
    close = float(r['cday'])
    res = {'CLOSE': net(eo, close), 'T30': net(eo, float(r['t30o']) if r['t30o'] is not None else close)}
    if eo > row['orl']:
        if r['ho'] is not None:
            ho = float(r['ho']); px = ho if ho < row['orl'] else row['orl']
        else:
            px = close
        res['STOP_OR'] = net(eo, px)
    return res


def kst(t):
    return (t + dt.timedelta(hours=9)).time()


def later_entries(row, bars, kind):
    """S3·S4 진입 (진입 시각, 진입 봉 인덱스) 또는 None."""
    idx = [i for i, b in enumerate(bars) if dt.time(9, 10) <= kst(b[0]) <= dt.time(10, 20)]
    for i in idx:
        if kind == 'S3':
            if i + 2 < len(bars) and float(bars[i][3]) <= row['vwap'] and float(bars[i + 1][4]) > row['vwap']:
                j = i + 2
            else: continue
        else:
            if float(bars[i][4]) > row['orh'] and i + 1 < len(bars): j = i + 1
            elif float(bars[i][4]) > row['orh']: return None
            else: continue
        if kst(bars[j][0]) <= dt.time(10, 30): return j
        return None
    return None


def exits_later(row, bars, j):
    entry = float(bars[j][1])
    if entry >= row['pc'] * 1.29: return None
    close = float(bars[-1][4]); t0 = bars[j][0]
    t30 = next((float(b[1]) for b in bars[j:] if b[0] >= t0 + dt.timedelta(minutes=30)), close)
    res = {'CLOSE': net(entry, close), 'T30': net(entry, t30)}
    if entry > row['orl']:
        px = close
        for b in bars[j:]:
            if float(b[3]) <= row['orl']:
                px = float(b[1]) if float(b[1]) < row['orl'] else row['orl']; break
        res['STOP_OR'] = net(entry, px)
    return res


def trades(data):
    feats = features(data); T = defaultdict(lambda: defaultdict(list))  # strat -> day -> [(exit, net, gross)]
    for d, rows in feats.items():
        for row in rows:  # CTRL
            ex = exits_0910(row)
            if ex: T['CTRL'][d].append(ex)
        for s in ('S1', 'S2'):
            cand = sorted([r for r in rows if r[s.lower()]], key=lambda r: (-r['rvol'], r['code']))
            picked = []
            for r in cand:
                ex = exits_0910(r)
                if ex: picked.append(ex)
                if len(picked) == TOP: break
            if picked: T[s][d] = picked
        pool = data['pool_bars'].get(d, {})
        for s in ('S3', 'S4'):
            cand = []
            for r in rows:
                if not r['s2'] or r['sid'] not in pool: continue
                bars = pool[r['sid']]; j = later_entries(r, bars, s)
                if j is None: continue
                ex = exits_later(r, bars, j)
                if ex: cand.append((bars[j][0], -r['rvol'], r['code'], ex))
            cand.sort(key=lambda x: x[:3])
            if cand: T[s][d] = [x[3] for x in cand[:TOP]]
    return T


def daily(T, strat, exit_, period, gross=False):
    out = {}
    for d, lst in T[strat].items():
        if period[0] <= d <= period[1]:
            vals = [e[exit_][1 if gross else 0] for e in lst if exit_ in e]
            if vals: out[d] = sum(vals) / len(vals)
    return out


def boot(series, reps=10000, block=5, seed=20261117):
    import random
    rnd = random.Random(seed); n = len(series)
    if n < block: return (float('nan'), float('nan'))
    means = []
    for _ in range(reps):
        s = []
        while len(s) < n:
            k = rnd.randrange(n); s.extend(series[(k + i) % n] for i in range(block))
        means.append(sum(s[:n]) / n)
    means.sort(); return means[int(0.025 * reps)], means[int(0.975 * reps)]


def mean(x): return sum(x) / len(x) if x else float('nan')


def analyze(path):
    data = pickle.load(open(path, 'rb')); T = trades(data)
    pct = lambda v: f"{v * 100:+.3f}%"
    print("전략 청산 기간 | 거래 거래일 | Gross Net [95%] | CTRL대비 | 플러스날 | 승률")
    verdict = {}
    for s in ('S1', 'S2', 'S3', 'S4'):
        for ex in ('CLOSE', 'STOP_OR', 'T30'):
            stats = {}
            for name, per in (('탐색', EXPLORE), ('확인', CONFIRM), ('추가', HOLDOUT)):
                dn = daily(T, s, ex, per); dg = daily(T, s, ex, per, True); dc = daily(T, 'CTRL', ex, per)
                days_ = sorted(dn); series = [dn[d] for d in days_]
                n_tr = sum(len([e for e in T[s][d] if ex in e]) for d in days_)
                wins = [e[ex][0] > 0 for d in days_ for e in T[s][d] if ex in e]
                exc = [dn[d] - dc[d] for d in days_ if d in dc]
                ci = boot(series) if name == '탐색' else (float('nan'), float('nan'))
                stats[name] = dict(n=n_tr, days=len(days_), net=mean(series), gross=mean([dg[d] for d in days_]),
                                   ci=ci, exc=mean(exc), pos=mean([v > 0 for v in series]), win=mean(wins))
                st = stats[name]
                print(f"{s} {ex:7} {name} | {st['n']:5} {st['days']:4} | {pct(st['gross'])} {pct(st['net'])} "
                      f"[{pct(ci[0]) if name == '탐색' else '-'}, {pct(ci[1]) if name == '탐색' else '-'}] | {pct(st['exc'])} | "
                      f"{st['pos'] * 100:.0f}% | {st['win'] * 100:.0f}%")
            e, c = stats['탐색'], stats['확인']
            checks = [e['net'] > 0 and c['net'] > 0, e['ci'][0] > 0, e['exc'] > 0 and c['exc'] > 0, c['pos'] >= 0.5,
                      e['n'] >= 100 and c['n'] >= 100 and e['days'] >= 40 and c['days'] >= 40]
            verdict[(s, ex)] = checks
            print(f"  -> 기준 {['O' if x else 'X' for x in checks]} {'PASS' if all(checks) else 'FAIL'}")
    print("\n추격 차단 효과 S2 - S1 (둘 다 거래한 날)")
    for ex in ('CLOSE', 'STOP_OR', 'T30'):
        res = {}
        for name, per in (('탐색', EXPLORE), ('확인', CONFIRM), ('추가', HOLDOUT)):
            a = daily(T, 'S2', ex, per); b = daily(T, 'S1', ex, per)
            ds = sorted(set(a) & set(b)); diff = [a[d] - b[d] for d in ds]
            ci = boot(diff) if name == '탐색' else (float('nan'), float('nan'))
            res[name] = (mean(diff), ci, len(ds))
            print(f"{ex:7} {name} 차이 {pct(mean(diff))} 날 {len(ds)}" + (f" [{pct(ci[0])}, {pct(ci[1])}]" if name == '탐색' else ''))
        ok = res['탐색'][0] > 0 and res['확인'][0] > 0 and res['탐색'][1][0] > 0
        print(f"  -> {'효과 있음' if ok else '효과 없음'}")
    print("\nCTRL (대상 전체, 09:10 진입)")
    for ex in ('CLOSE', 'STOP_OR', 'T30'):
        for name, per in (('탐색', EXPLORE), ('확인', CONFIRM), ('추가', HOLDOUT)):
            dn = daily(T, 'CTRL', ex, per)
            print(f"{ex:7} {name} 날 {len(dn)} Net {pct(mean(list(dn.values())))} 평균 종목수 "
                  f"{mean([len(T['CTRL'][d]) for d in dn]):.0f}")
    print("\n합격 조합:", [k for k, v in verdict.items() if all(v)] or '없음')


if __name__ == '__main__':
    if sys.argv[1] == 'fetch': fetch(sys.argv[2], sys.argv[3])
    else: analyze(sys.argv[2])
