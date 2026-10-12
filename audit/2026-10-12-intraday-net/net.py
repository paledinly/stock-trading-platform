import csv, sys, statistics as st, json
rows=list(csv.DictReader(open(sys.argv[1],encoding='utf-8'),delimiter='\t'))
SC={'LOW':dict(bf=.015,sf=.015,tax=.20,spr=.05,bs=0,ss=0),
    'BASE':dict(bf=.015,sf=.015,tax=.20,spr=.10,bs=.03,ss=.03),
    'HIGH':dict(bf=.015,sf=.015,tax=.20,spr=.15,bs=.05,ss=.05)}
def buy(c,p): return p*(1+(c['spr']/2+c['bs'])/100)
def sell(c,p): return p*(1-(c['spr']/2+c['ss'])/100)
def net(c,e,x): return (x*(1-(c['sf']+c['tax'])/100)/(e*(1+c['bf']/100))-1)*100
DONE={'STOPPED','TARGET_HIT','TRAILING_EXIT','TIME_EXIT'}
def stats(v):
    if not v: return dict(n=0)
    w=[x for x in v if x>0]; l=[x for x in v if x<=0]
    return dict(n=len(v),win=round(len(w)/len(v)*100,1),avg=round(st.mean(v),3),med=round(st.median(v),3),
                pf=round(sum(w)/-sum(l),3) if l and sum(l)<0 else None)
out={}
for name,c in SC.items():
    for cohort in ['ALL','STOCK']:
        by={}; gate={}
        for r in rows:
            if cohort=='STOCK' and r['is_etf']=='t': continue
            d=r['session_date']
            to,stop,t1=float(r['entry_to']),float(r['stop']),float(r['target1'])
            rr=net(c,buy(c,to),sell(c,t1))/-net(c,buy(c,to),sell(c,stop))
            g=gate.setdefault(d,[0,0]); g[0]+=1; g[1]+= rr>=1.5
            if r['status'] in DONE and r['uncertain']!='true' and r['entry_ref'] and r['exit_price']:
                n=net(c,buy(c,float(r['entry_ref'])),sell(c,float(r['exit_price'])))
                by.setdefault(d,[]).append((n,float(r['gross']),rr>=1.5))
        res={}
        for d in sorted(set(by)|set(gate)):
            v=by.get(d,[])
            res[d]=dict(recs=gate[d][0],pass_cost_rr=gate[d][1],gross=stats([x[1] for x in v]),net=stats([x[0] for x in v]),
                        net_if_cost_gate=stats([x[0] for x in v if x[2]]))
        allv=[x for d in by for x in by[d]]
        res['TOTAL']=dict(recs=sum(g[0] for g in gate.values()),pass_cost_rr=sum(g[1] for g in gate.values()),
            gross=stats([x[1] for x in allv]),net=stats([x[0] for x in allv]),net_if_cost_gate=stats([x[0] for x in allv if x[2]]))
        out[f'{name}/{cohort}']=res
json.dump(dict(scenarios=SC,results=out),open('net_results.json','w'),indent=1)
for k in ['BASE/STOCK','BASE/ALL']:
    print('==',k)
    for d,r in out[k].items():
        print(d,'추천',r['recs'],'비용RR통과',r['pass_cost_rr'],'| Gross',r['gross'],'| Net',r['net'],'| 비용gate후 Net',r['net_if_cost_gate'])
for k in ['LOW/STOCK','HIGH/STOCK']:
    t=out[k]['TOTAL']; print('==',k,'Net',t['net'],'gate후',t['net_if_cost_gate'],'통과',t['pass_cost_rr'],'/',t['recs'])
