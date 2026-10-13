"""Pool RunComparison reports per variant. Stocks only (ETF flag from the 10-12 export), BASE costs from variants.json.

usage: python -I summarize.py <recs.tsv> <report.json>...  -> prints tables, writes summary.json next to this file
"""
import collections, csv, json, pathlib, sys

etf = {}
with open(sys.argv[1], encoding='utf-8') as f:
    for row in csv.DictReader(f, delimiter='\t'):
        etf[row['stock_code']] = row['is_etf'] in ('t', 'true')


def baseline_p(sig, o):
    entry = o.get('entryReferencePrice') or o.get('entryPrice')
    if entry is None:
        return None
    stop, target = entry - sig['plan']['stop'], sig['plan']['target1'] - entry
    return stop / (stop + target) if stop > 0 and target > 0 else None


def complete(o):
    return o['exitAt'] is not None and o['grossReturn'] is not None and not o['uncertain']


rows = collections.defaultdict(list)          # variant -> [(date, id, signal, outcome)]
for path in sys.argv[2:]:
    report = json.load(open(path, encoding='utf-8'))
    date = pathlib.Path(path).stem
    for name, group in report['comparisons'].items():
        for r in group['results']:
            if etf.get(r['signal']['stockCode'], False):
                continue
            rows[name].append((date, r['signal']['id'], r['signal'], r['outcome']))

base = {(d, i): o for d, i, _, o in rows['BASELINE']}
summary = {}
for name, items in rows.items():
    done = [(d, i, s, o) for d, i, s, o in items if complete(o) and o['netReturn'] is not None]
    no_entry = sum(1 for *_, o in items if o['entryAt'] is None and not o['uncertain'])
    uncertain = sum(1 for *_, o in items if o['uncertain'])
    nets = [o['netReturn'] for *_, o in done]
    gains, losses = sum(n for n in nets if n > 0), -sum(n for n in nets if n < 0)
    ordered = [(s, o) for _, _, s, o in done if o['targetBeforeStop'] is not None]
    comp = [(s, o, baseline_p(s, o)) for s, o in ordered if baseline_p(s, o) is not None]
    paired = [o['netReturn'] - base[(d, i)]['netReturn'] for d, i, s, o in done
              if (d, i) in base and complete(base[(d, i)]) and base[(d, i)]['netReturn'] is not None]
    missed = sum(1 for d, i, s, o in items if o['entryAt'] is None and not o['uncertain'] and (d, i) in base
                 and complete(base[(d, i)]) and base[(d, i)]['netReturn'] is not None and base[(d, i)]['netReturn'] > 0)
    days = collections.defaultdict(list)
    for d, _, _, o in done:
        days[d].append(o['netReturn'])
    summary[name] = {
        'recommendations': len(items), 'completedTrades': len(done), 'noEntry': no_entry, 'uncertain': uncertain,
        'avgGross': sum(o['grossReturn'] for *_, o in done) / len(done) if done else None,
        'avgNet': sum(nets) / len(nets) if nets else None,
        'netWinRate': 100 * sum(n > 0 for n in nets) / len(nets) if nets else None,
        'netPF': gains / losses if losses else None,
        'netPointsPerRecommendation': sum(nets) / len(items) if items else None,
        'targetFirstRate': 100 * sum(o['targetBeforeStop'] for _, o in ordered) / len(ordered) if ordered else None,
        'randomBaseline': 100 * sum(p for *_, p in comp) / len(comp) if comp else None,
        'targetEdge': (100 * sum(o['targetBeforeStop'] for _, o, _ in comp) / len(comp)
                       - 100 * sum(p for *_, p in comp) / len(comp)) if comp else None,
        'pairedWithBaseline': len(paired), 'pairedNetDiff': sum(paired) / len(paired) if paired else None,
        'missedBaselineNetWinners': missed,
        'daysPositiveAvgNet': sum(1 for v in days.values() if sum(v) / len(v) > 0), 'daysWithTrades': len(days),
    }


def f(v, digits=3):
    return '-' if v is None else f'{v:.{digits}f}' if isinstance(v, float) else str(v)


cols = ['recommendations', 'completedTrades', 'noEntry', 'uncertain', 'avgGross', 'avgNet', 'netWinRate', 'netPF',
        'netPointsPerRecommendation', 'targetFirstRate', 'randomBaseline', 'targetEdge', 'pairedWithBaseline',
        'pairedNetDiff', 'missedBaselineNetWinners', 'daysPositiveAvgNet', 'daysWithTrades']
print('| variant | ' + ' | '.join(cols) + ' |')
print('|' + ' --- |' * (len(cols) + 1))
for name, s in summary.items():
    print(f'| {name} | ' + ' | '.join(f(s[c]) for c in cols) + ' |')
out = pathlib.Path(__file__).with_name('summary.json')
out.write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding='utf-8')
