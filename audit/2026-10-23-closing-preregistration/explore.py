"""Exploration set for the 2026-10 pre-registration (ADR-010 excluded segment, evaluations 10-14..10-21).

Reads only stored rows. Prints descriptive tables; nothing here is a judgment.
Connection values come from the repository .env and are never printed.
"""
import json
from pathlib import Path
import re
import statistics as st
from collections import defaultdict

import psycopg

ENV = Path(__file__).resolve().parents[2] / '.env'  # repository .env, never printed
RUNS = (64, 65, 66, 67, 68, 69)          # FORWARD runs, recommendation dates 10-14..10-21
BUY, SELL = 0.00095, 0.00295            # ADR-008 BASE costs
BUY_H, SELL_H = 0.00165, 0.00365        # ADR-008 HIGH costs
DATA_GATES = {'RECEIVED_AFTER_EVALUATION', 'STALE_FEATURE', 'CANDLE_GAP', 'DAILY_DATA_MISSING', 'DAILY_DATA_STALE',
              'MISSING_REQUIRED_FEATURES', 'INSUFFICIENT_INTRADAY_COVERAGE', 'CALENDAR_UNVERIFIED', 'LOW_LIQUIDITY'}


def connect():
    env = {}
    for line in open(ENV, encoding='utf-8'):
        line = line.strip()
        if line and not line.startswith('#') and '=' in line:
            k, v = line.split('=', 1)
            env[k.strip()] = v.strip().strip('"').strip("'")
    m = re.match(r'jdbc:postgresql://([^:/]+)(?::(\d+))?/([^?]+)', env['DB_URL'])
    c = psycopg.connect(host=m[1], port=int(m[2] or 5432), dbname=m[3], user=env['DB_USERNAME'],
                        password=env['DB_PASSWORD'], sslmode='require')
    c.execute("set time zone 'Asia/Seoul'")
    return c


def net(entry, exit_, b=BUY, s=SELL):
    return (exit_ * (1 - s) - entry * (1 + b)) / (entry * (1 + b)) * 100


def day_mean(rows, key):
    by_day = defaultdict(list)
    for r in rows:
        if r.get(key) is not None:
            by_day[r['obs']].append(r[key])
    days = {d: st.mean(v) for d, v in sorted(by_day.items())}
    return days, (st.mean(days.values()) if days else None), sum(len(v) for v in by_day.values())


def summary(label, rows):
    out = [label, f'n={len(rows)}']
    for key in ('base', 'next_open'):
        days, mean_of_days, n = day_mean(rows, key)
        vals = [r[key] for r in rows if r.get(key) is not None]
        if vals:
            win = sum(v > 0 for v in vals) / len(vals) * 100
            pos = sum(v for v in vals if v > 0)
            neg = -sum(v for v in vals if v < 0)
            pf = pos / neg if neg else float('inf')
            out.append(f'{key}: mean {st.mean(vals):+.2f}% dayMean {mean_of_days:+.2f}% win {win:.0f}% PF {pf:.2f}')
    print(' | '.join(out))


def main():
    c = connect()
    snaps = {rid: json.loads(s) for rid, s in c.execute(
        'select id, response_snapshot from closing_recommendation_run where id = any(%s)', (list(RUNS),))}
    rows = c.execute("""
        select o.run_id, s.stock_code, o.expected_session_date, o.disposition, o.decision_reason, o.final_score,
               o.entry_price, o.net_return_rate, o.exit_reason,
               (select c5.open from stock_candle c5 where c5.stock_id = o.stock_id and c5.timeframe = '5M'
                  and c5.start_time = (o.expected_session_date + time '09:00') at time zone 'Asia/Seoul'),
               x.market_warning_code, x.investment_caution, x.short_term_overheat, x.vi_code, x.change_rate
          from closing_candidate_observation o join stock s on s.id = o.stock_id
          left join lateral (select * from closing_candidate_context cc where cc.run_id = o.run_id
                and cc.stock_code = s.stock_code and cc.error is null order by cc.received_at limit 1) x on true
         where o.run_id = any(%s) and o.candidate_source = 'PRECISION' and o.status = 'COMPLETED'""", (list(RUNS),)).fetchall()
    data = []
    for (rid, code, obs, disp, reason, score, entry, base, exit_reason, open0, warn, caution, overheat, vi, chg) in rows:
        ev = next((e for e in snaps[rid]['evaluations'] if e['stockCode'] == code and e['candidateSource'] == 'PRECISION'), {})
        data.append(dict(run=rid, code=code, obs=str(obs), disp=disp, reason=reason, score=float(score or 0),
                         opp=ev.get('opportunityScore'), risk=ev.get('riskScore'),
                         missing=set(ev.get('missingFeatures') or []), base=float(base) if base is not None else None,
                         next_open=net(float(entry), float(open0)) if entry and open0 else None,
                         next_open_high=net(float(entry), float(open0), BUY_H, SELL_H) if entry and open0 else None,
                         exit=exit_reason, warn=warn, caution=caution, overheat=overheat, vi=vi,
                         chg=float(chg) if chg is not None else None))

    print('== exploration set: PRECISION, COMPLETED, runs', RUNS)
    summary('ALL', data)
    print('days:', sorted({d['obs'] for d in data}))

    print('\n== A2 selection rules (same entries, BASELINE / NEXT_OPEN)')
    clean = [d for d in data if not (d['missing'] & DATA_GATES) and d['reason'] not in DATA_GATES]
    summary('clean (no data-quality gate)', clean)
    for n in (1, 3, 5):
        top = []
        for day in sorted({d['obs'] for d in clean}):
            top += sorted([d for d in clean if d['obs'] == day], key=lambda d: -d['score'])[:n]
        summary(f'top{n} by finalScore (clean)', top)
    for th in (35, 30, 25):
        summary(f'opp>={th} (clean)', [d for d in clean if (d['opp'] or 0) >= th])
    summary('SELECTED (official)', [d for d in data if d['disp'] == 'SELECTED'])
    summary('WATCH (official)', [d for d in data if d['disp'] == 'WATCH'])
    summary('OVEREXTENDED only', [d for d in data if d['reason'] == 'OVEREXTENDED'])

    print('\n== H-A market status at 15:16 context')
    def flagged(d):
        return ((d['warn'] or '00') not in ('00', '') or (d['caution'] or 'N') == 'Y' or (d['overheat'] or 'N') == 'Y')
    with_ctx = [d for d in data if d['warn'] is not None or d['caution'] is not None]
    summary('flagged (warning/caution/overheat)', [d for d in with_ctx if flagged(d)])
    summary('not flagged', [d for d in with_ctx if not flagged(d)])
    print('context missing:', len(data) - len(with_ctx))

    print('\n== H-B evaluation-day KOSPI (intraday at <=15:15) vs next-day result')
    for day in sorted({d['obs'] for d in data}):
        rec = next(d['run'] for d in data if d['obs'] == day)
        r = c.execute("""select payload from closing_context_observation where kind='INDEX' and symbol='KOSPI'
                          and received_at <= (select evaluated_as_of from closing_recommendation_run where id=%s)
                          and received_at >= (select evaluated_as_of from closing_recommendation_run where id=%s) - interval '1 day'
                          order by received_at desc limit 1""", (rec, rec)).fetchone()
        kospi = json.loads(r[0]).get('returnPct') if r else None
        sub = [d for d in data if d['obs'] == day]
        print(f"obs {day} run {rec}: KOSPI@eval {kospi} | n={len(sub)} base {st.mean([d['base'] for d in sub]):+.2f}% "
              f"next_open {st.mean([d['next_open'] for d in sub if d['next_open'] is not None]):+.2f}%")

    print('\n== exit mix (BASELINE)')
    mix = defaultdict(int)
    for d in data:
        mix[d['exit']] += 1
    print(dict(mix))


if __name__ == '__main__':
    main()
