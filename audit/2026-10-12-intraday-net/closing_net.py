import csv,sys,statistics as st,collections,json
rows=list(csv.DictReader(open(sys.argv[1],encoding='utf-8'),delimiter='\t'))
BUY=0.05+0.03+0.015; SELL=0.05+0.03+0.015+0.20   # BASE: spread/2+slip+fee (+tax)
def net(g): return ((1+g/100)*(1-SELL/100)/(1+BUY/100)-1)*100
T,S=3.0,-2.0
res=collections.defaultdict(lambda: collections.defaultdict(list)); cover=collections.Counter()
for r in rows:
    if r['is_etf']=='t' or not r['signal_price']: continue
    cover[(r['disposition'],bool(r['close']))]+=1
    if not r['close']: continue
    e=float(r['signal_price']); o,h,l,c=(float(r[k]) for k in('open','high','low','close'))
    pct=lambda p:(p/e-1)*100
    if pct(o)>=T: opt=pes=pct(o)            # gap over target: exit at open
    elif pct(o)<=S: opt=pes=pct(o)
    else:
        th,sh=pct(h)>=T,pct(l)<=S
        if th and sh: opt,pes=T,S          # order unknown from daily bar
        elif th: opt=pes=T
        elif sh: opt=pes=S
        else: opt=pes=pct(c)
    grp='SELECTED+WATCH' if r['disposition'] in('SELECTED','WATCH') else 'EXCLUDED'
    for g in (grp,'ALL'):
        res[g]['pes'].append(pes); res[g]['opt'].append(opt); res[g]['c2c'].append(pct(c)); res[g]['open'].append(pct(o))
        res[g]['day'].append(r['recommendation_date'])
def s(v):
    w=[x for x in v if x>0]; l=[x for x in v if x<=0]
    return dict(n=len(v),win=round(len(w)/len(v)*100,1),avg=round(st.mean(v),3),med=round(st.median(v),3),pf=round(sum(w)/-sum(l),2) if sum(l)<0 else None)
out={}
print('coverage',dict(cover))
for g,d in res.items():
    out[g]=dict(gross_pessimistic=s(d['pes']),net_pessimistic=s([net(x) for x in d['pes']]),
                net_optimistic=s([net(x) for x in d['opt']]),open_gap=s(d['open']),close_to_close_net=s([net(x) for x in d['c2c']]))
    byday=collections.defaultdict(list)
    for dy,x in zip(d['day'],d['pes']): byday[dy].append(net(x))
    out[g]['net_pessimistic_by_day']={k:dict(n=len(v),avg=round(st.mean(v),3)) for k,v in sorted(byday.items())}
    print('==',g); [print(' ',k,v) for k,v in out[g].items()]
json.dump(out,open('closing_net_results.json','w'),indent=1,ensure_ascii=False)
