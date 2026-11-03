"""Step 1 of the subscription replay: how close are the reconstructed rankings to what KIS actually returned?

Real: market_broad_snapshot rows (2026-09-08..2026-10-22) carry, per scan, the ranks a stock had in each KIS ranking.
Rows are grouped by scan (trading day + capture minute). Reconstructed: the top 30 of each ranking at the last
5-minute bar end before the scan, over tradable stocks in the research data (same flags as the Broad collector).
Reported per ranking: recall = share of the stocks KIS ranked that the reconstruction also ranks in its top 30.
No returns are used. Run: python validate.py
"""
import datetime as dt
import json
from collections import defaultdict

import numpy as np

from replay_common import TYPES, connect, load, point_of, rankings

FROM, TO = dt.date(2026, 9, 8), dt.date(2026, 10, 22)


def main():
    c = connect()
    data = load(c)
    by_day = defaultdict(list)
    for i, d in enumerate(data['day']):
        by_day[int(d)].append(i)
    tradable = {s for s, m in data['meta'].items() if m[3]}
    # Group by the time each ranking was received (observation receivedAt), not by the row's capture time:
    # enrichment re-captures rows later and would pair old ranks with a later reconstruction.
    scans = defaultdict(lambda: defaultdict(set))
    seen = set()
    for s, src in c.execute(
            """select stock_id, ranking_sources from market_broad_snapshot
                where session_date between %s and %s and ranking_sources is not null""", (FROM, TO)):
        try:
            observations = json.loads(src).get('observations') or []
        except (ValueError, AttributeError):
            continue
        for ob in observations:
            raw = ob['receivedAt'].rstrip('Z')
            if '.' in raw:
                head, frac = raw.split('.', 1)
                raw = f'{head}.{frac[:6]}'
            at = dt.datetime.fromisoformat(raw).replace(tzinfo=dt.timezone.utc)
            local = at.astimezone(dt.timezone(dt.timedelta(hours=9)))
            key = (local.date().toordinal(), local.replace(second=0, microsecond=0).time())
            if (key, ob['type'], s) in seen:
                continue
            seen.add((key, ob['type'], s))
            if s in tradable:                     # early snapshots also stored ETFs; compare tradable stocks only
                scans[key][ob['type']].add(s)
    recall = defaultdict(list)
    union_recall, n_scans = [], 0
    for (d, t), real in sorted(scans.items()):
        p = point_of(t)
        if p is None or p >= 70 or d not in by_day:
            continue
        rows = np.array([i for i in by_day[d] if int(data['sid'][i]) in tradable])
        recon = rankings(data, rows, p)
        recon_sets = {k: {int(data['sid'][i]) for i, _ in v} for k, v in recon.items()}
        for k in TYPES:
            if real.get(k):
                recall[k].append(len(real[k] & recon_sets[k]) / len(real[k]))
        ru = set().union(*real.values())
        cu = set().union(*recon_sets.values())
        if ru:
            union_recall.append(len(ru & cu) / len(ru))
        n_scans += 1
    print(f'scans compared: {n_scans} ({FROM}..{TO})')
    for k in TYPES:
        v = recall[k]
        if v:
            print(f'  {k:15s} recall mean {np.mean(v) * 100:5.1f}%  median {np.median(v) * 100:5.1f}%  scans {len(v)}')
    print(f'  {"UNION":15s} recall mean {np.mean(union_recall) * 100:5.1f}%  median {np.median(union_recall) * 100:5.1f}%')


if __name__ == '__main__':
    main()
