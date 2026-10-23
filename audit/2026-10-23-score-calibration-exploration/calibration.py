"""Exploratory score calibration (2026-10-23). Hypothesis generation only, not a judgment.

Sample: official FORWARD runs with recommendation dates 2026-09-22..2026-10-21 (before the ADR-010 confirmation
sample that starts with the 2026-10-23 evaluation). Every PRECISION candidate in the stored evaluation snapshot,
with the scores and decision reason computed live at the time.

Outcome (daily-bar approximation): entry = the evaluation's referencePrice; next Korean trading day d' from
daily_adjusted_candle. NEXT_OPEN = open[d'], CC = close[d'], BASELINE = daily approximation of the +3%/-2% rule
(open beyond either level fills at the open, both touched -> stop). ADR-008 BASE costs.
Candidates whose adjusted close on d differs from referencePrice by more than 15% are dropped (corporate action
or price mismatch).

Because candidates of the same day move together with the market, every comparison uses the within-day excess
(candidate Net minus that day's mean Net) and days are resampled (10,000 draws, seed 20261023) for 95% intervals.
Run: python calibration.py   (reads the repository .env, never prints credentials)
"""
import json
import random
import re
import statistics as st
from collections import defaultdict
from pathlib import Path

import psycopg

ENV = Path(__file__).resolve().parents[2] / '.env'
LAST_EVALUATION = '2026-10-21'
BUY, SELL = 0.00095, 0.00295
TARGET, STOP = 0.03, -0.02
DRAWS, SEED = 10_000, 20261023


def connect():
    env = {}
    for line in open(ENV, encoding='utf-8'):
        line = line.strip()
        if line and not line.startswith('#') and '=' in line:
            k, v = line.split('=', 1)
            env[k.strip()] = v.strip().strip('"').strip("'")
    m = re.match(r'jdbc:postgresql://([^:/]+)(?::(\d+))?/([^?]+)', env['DB_URL'])
    return psycopg.connect(host=m[1], port=int(m[2] or 5432), dbname=m[3], user=env['DB_USERNAME'],
                           password=env['DB_PASSWORD'], sslmode='require')


def net(entry, exit_):
    return (exit_ * (1 - SELL) - entry * (1 + BUY)) / (entry * (1 + BUY)) * 100


def baseline(entry, o, h, l, c):
    if o / entry - 1 <= STOP or o / entry - 1 >= TARGET:
        return o
    up, down = h / entry - 1 >= TARGET, l / entry - 1 <= STOP
    if up and down:
        return entry * (1 + STOP)
    if up:
        return entry * (1 + TARGET)
    if down:
        return entry * (1 + STOP)
    return c


def load():
    c = connect()
    runs = c.execute("""select distinct on (recommendation_date) id, recommendation_date, strategy_version, response_snapshot
                          from closing_recommendation_run where execution_mode = 'FORWARD' and completed_at is not null
                           and recommendation_date <= %s order by recommendation_date, completed_at desc""",
                     (LAST_EVALUATION,)).fetchall()
    rows, dropped = [], 0
    for rid, d, version, snap in runs:
        for e in (json.loads(snap) if snap else {}).get('evaluations') or []:
            if e.get('candidateSource') != 'PRECISION' or not e.get('referencePrice') or e.get('finalScore') is None:
                continue
            bar = c.execute("""select a.close_price, n.open_price, n.high_price, n.low_price, n.close_price
                                 from daily_adjusted_candle a join stock s on s.id = a.stock_id
                                 join lateral (select * from daily_adjusted_candle x where x.stock_id = a.stock_id
                                       and x.trade_date > a.trade_date order by x.trade_date limit 1) n on true
                                where s.stock_code = %s and a.trade_date = %s""", (e['stockCode'], d)).fetchone()
            if not bar or not bar[1]:
                dropped += 1
                continue
            entry = float(e['referencePrice'])
            close_d, o, h, l, cl = map(float, bar)
            if abs(close_d / entry - 1) > 0.15:
                dropped += 1
                continue
            rows.append(dict(day=str(d), version=version, code=e['stockCode'], score=float(e['finalScore']),
                             opp=float(e.get('opportunityScore') or 0), risk=float(e.get('riskScore') or 0),
                             reason=e.get('decisionReason'), disposition=e.get('disposition'),
                             next_open=net(entry, o), baseline=net(entry, baseline(entry, o, h, l, cl)), cc=net(entry, cl)))
    return rows, dropped


def with_excess(rows, key):
    by_day = defaultdict(list)
    for r in rows:
        by_day[r['day']].append(r[key])
    means = {d: st.mean(v) for d, v in by_day.items()}
    for r in rows:
        r['x_' + key] = r[key] - means[r['day']]


def group_ci(rows, key, rng):
    """Mean within-day excess of a group, resampling days."""
    by_day = defaultdict(list)
    for r in rows:
        by_day[r['day']].append(r[key])
    days = list(by_day)
    if len(days) < 3:
        return st.mean(r[key] for r in rows), None, None
    draws = []
    for _ in range(DRAWS):
        pick = [days[rng.randrange(len(days))] for _ in days]
        vals = [v for d in pick for v in by_day[d]]
        draws.append(st.mean(vals))
    draws.sort()
    return st.mean(r[key] for r in rows), draws[int(DRAWS * 0.025)], draws[int(DRAWS * 0.975) - 1]


def spearman(a, b):
    def ranks(v):
        order = sorted(range(len(v)), key=lambda i: v[i])
        r = [0.0] * len(v)
        for pos, i in enumerate(order):
            r[i] = pos
        return r
    ra, rb = ranks(a), ranks(b)
    ma, mb = st.mean(ra), st.mean(rb)
    num = sum((x - ma) * (y - mb) for x, y in zip(ra, rb))
    den = (sum((x - ma) ** 2 for x in ra) * sum((y - mb) ** 2 for y in rb)) ** 0.5
    return num / den if den else 0.0


def main():
    rows, dropped = load()
    for key in ('next_open', 'baseline', 'cc'):
        with_excess(rows, key)
    days = sorted({r['day'] for r in rows})
    print(f'candidates {len(rows)} (dropped {dropped}) days {len(days)} {days[0]}..{days[-1]}')
    print('day mean NEXT_OPEN/BASELINE/CC: ' + ' | '.join(
        f"{d[5:]} {st.mean(r['next_open'] for r in rows if r['day'] == d):+.2f}/"
        f"{st.mean(r['baseline'] for r in rows if r['day'] == d):+.2f}" for d in days))
    rng = random.Random(SEED)

    def report(title, groups):
        print(f'\n== {title} (mean within-day excess Net, 95% day-resampled)')
        for label, members in groups:
            if not members:
                continue
            parts = []
            for key in ('next_open', 'baseline'):
                mean, lo, hi = group_ci(members, 'x_' + key, rng)
                parts.append(f"{key} {mean:+.2f}%" + (f" [{lo:+.2f},{hi:+.2f}]" if lo is not None else ''))
            print(f'  {label:32s} n={len(members):3d} days={len({m["day"] for m in members}):2d} | ' + ' | '.join(parts))

    def tercile(key):
        out = {'low': [], 'mid': [], 'high': []}
        for d in days:
            day = sorted([r for r in rows if r['day'] == d], key=lambda r: r[key])
            n = len(day)
            for i, r in enumerate(day):
                out['low' if i < n / 3 else 'mid' if i < 2 * n / 3 else 'high'].append(r)
        return [(f'{key} within-day {k}', v) for k, v in out.items()]

    report('final score, within-day terciles', tercile('score'))
    report('opportunity score, within-day terciles', tercile('opp'))
    report('risk score, within-day terciles', tercile('risk'))
    report('final score, absolute bands', [('score < 30', [r for r in rows if r['score'] < 30]),
                                           ('30 <= score < 55', [r for r in rows if 30 <= r['score'] < 55]),
                                           ('score >= 55', [r for r in rows if r['score'] >= 55])])
    reasons = defaultdict(list)
    for r in rows:
        reasons[r['reason']].append(r)
    report('decision reason (gate that excluded the candidate)',
           sorted(reasons.items(), key=lambda kv: -len(kv[1])))
    report('version', [(v, [r for r in rows if r['version'] == v]) for v in sorted({r['version'] for r in rows})])

    print('\n== Spearman(score, Net) per day, mean over days (95% day-resampled)')
    for key in ('next_open', 'baseline'):
        per_day = []
        for d in days:
            day = [r for r in rows if r['day'] == d]
            if len(day) >= 5:
                per_day.append(spearman([r['score'] for r in day], [r[key] for r in day]))
        draws = sorted(st.mean(per_day[rng.randrange(len(per_day))] for _ in per_day) for _ in range(DRAWS))
        print(f'  {key:9s} mean rho {st.mean(per_day):+.3f} [{draws[int(DRAWS * 0.025)]:+.3f}, {draws[int(DRAWS * 0.975) - 1]:+.3f}] '
              f'days {len(per_day)} positive {sum(x > 0 for x in per_day)}')


if __name__ == '__main__':
    main()
