"""Fixed-rule 1..14 market-session comparison on the prior read-only live DB snapshot."""
import argparse
import hashlib
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE=Path(__file__).resolve().parent
PRIOR=HERE.parent/'2026-11-09-five-session-study'
sys.path.insert(0,str(PRIOR))
import study as s


def save(name,value):
    (HERE/name).write_text(json.dumps(value,ensure_ascii=False,indent=2,default=str),encoding='utf-8')


def checks():
    s.selfcheck()
    c=np.full((40,2),100.);j=np.zeros_like(c,dtype=bool)
    for h in (1,5,10,14):
        p,b=s.paths(0,np.array([0]),c,c,j,horizon=h)
        assert p.shape==(h+1,1) and not b.any()
        assert abs(p[-1,0]-.99705/1.00095)<1e-12
        _,eq=s.account({0:p[:,0],h:p[:,0]},40,horizon=h)
        assert abs(eq[-1]-((h-1)/h+p[-1,0]**2/h))<1e-12
        x=c.copy();x[h,0]=np.nan
        p,b=s.paths(0,np.array([0]),x,c,j,horizon=h)
        assert b[0] and p[-1,0]<.5
    print('Horizon accounting and missing-exit checks passed.',flush=True)


def run():
    checks()
    payload=pd.read_pickle(PRIOR/'data/snapshot.pkl')
    days,cols,c,filled,ret,jumps,eligible,scores=s.prepare(payload,False)
    names=s.RULES+['LIQUID5']
    selections={};unfilled={};universes={}
    for t in range(len(days)):
        u=np.flatnonzero(eligible[t]); mask=np.isfinite(c[t])&(ret[t]<.29)&~jumps[t]
        universes[t]=(u,u[mask[u]])
        for name in names:
            score=scores[name].iloc[t].to_numpy()
            ix=np.flatnonzero(eligible[t]&np.isfinite(score))
            ix=ix[np.argsort(-score[ix],kind='stable')[:5]]
            selections[t,name]=ix[mask[ix]]
            unfilled[t,name]=int((~mask[ix]).sum())
    del scores
    results=[];curves=[];samples=[];five_compare=[];ledger_inputs={}
    for period,(start,end) in s.PERIODS.items():
        loc=np.array([i for i,d in enumerate(days) if start<=str(d)<=end]);ts=loc[:-14]
        for h in range(1,15):
            base={};base_cohorts={}
            for t in ts:
                u,ids=universes[t]
                if not len(u):
                    continue
                p,_=s.paths(t,ids,c,filled,jumps,horizon=h)
                base[t]=float(np.mean(p[-1]-1)*100) if len(ids) else 0.
                base_cohorts[t-loc[0]]=1+(p.sum(axis=1)-len(ids))/len(u)
            am,_=s.account(base_cohorts,len(loc),horizon=h)
            results.append({'period':period,'h':h,'rule':'ALL','net':s.boot(list(base.values())),
                            'account':am,'common_entry_days':len(ts)})
            for name in names:
                net=np.full(len(ts),np.nan);excess=net.copy();high=net.copy()
                trades=[];nuncertain=0;badfills=0;cohort={};hcohort={};years={};zvals=[];lvals=[]
                chosen_samples=[]
                for k,t in enumerate(ts):
                    ids=selections[t,name];badfills+=unfilled[t,name]
                    if not len(ids) or t not in base:
                        continue
                    p,bad=s.paths(t,ids,c,filled,jumps,horizon=h)
                    hp,_=s.paths(t,ids,c,filled,jumps,buy=.00165,sell=.00365,horizon=h)
                    zp,_=s.paths(t,ids,c,filled,jumps,haircut=0,horizon=h)
                    lp,_=s.paths(t,ids,c,filled,jumps,haircut=1,horizon=h)
                    vals=(p[-1]-1)*100
                    trades.extend(vals);nuncertain+=int(bad.sum())
                    zvals.extend((zp[-1]-1)*100);lvals.extend((lp[-1]-1)*100)
                    net[k]=vals.mean();high[k]=np.mean(hp[-1]-1)*100;excess[k]=net[k]-base[t]
                    years.setdefault(str(days[t].year),[]).append(float(net[k]))
                    cohort[t-loc[0]]=1+(p.sum(axis=1)-len(ids))/5
                    hcohort[t-loc[0]]=1+(hp.sum(axis=1)-len(ids))/5
                    if len(chosen_samples)<20:
                        for a,sid in enumerate(ids):
                            if not bad[a]:
                                chosen_samples.append((period,h,name,str(days[t]),str(days[t+h]),int(cols[sid]),float(vals[a])))
                    if h==5:
                        five_compare.extend((period,name,str(days[t]),int(cols[sid]),float(vals[a])) for a,sid in enumerate(ids))
                am,eq=s.account(cohort,len(loc),horizon=h)
                hm,_=s.account(hcohort,len(loc),horizon=h)
                if h==10 and period=='check_a' and nuncertain==0 and name in ['LOWVOL','BUYBACK_QUIET']:
                    ledger_inputs[name]=(loc,ts,eq)
                row={'period':period,'h':h,'rule':name,'common_entry_days':len(ts),
                     'first_entry':str(days[ts[0]]),'last_entry':str(days[ts[-1]]),
                     'fills':len(trades),'signal_days':int(np.isfinite(net).sum()),'unfilled':badfills,'uncertain':nuncertain,
                     'slot_use_pct':len(trades)/(len(ts)*5)*100,'net':s.boot(net),'excess':s.boot(excess),
                     'high_net':float(np.nanmean(high)),'trade_net':float(np.mean(trades)),
                     'median':float(np.median(trades)),'win_pct':float(np.mean(np.array(trades)>0)*100),
                     'cvar5':float(np.mean(np.sort(trades)[:max(1,len(trades)//20)])),
                     'stress_no_haircut':float(np.mean(zvals)),'stress_full_loss':float(np.mean(lvals)),
                     'year_net':{y:float(np.mean(v)) for y,v in years.items()},'account':am,'high_account':hm}
                results.append(row);samples.extend(chosen_samples[:20])
                curves.extend((period,h,name,str(days[d]),float(eq[k])) for k,d in enumerate(loc))
            print(period,'h=',h,'completed',flush=True)
        # All 140 combinations, not just the winning holding period.
        hypotheses=[r for r in results if r['period']==period and r['rule'] in s.RULES]
        assert len(hypotheses)==140
        for metric in ['net','excess']:
            last=0.
            for k,row in enumerate(sorted(hypotheses,key=lambda r:r[metric]['p'])):
                last=max(last,min(1.,(140-k)*row[metric]['p']))
                row[metric]['holm_p_140']=last
    indexed={(r['period'],r['h'],r['rule']):r for r in results}
    passed=[]
    for h in range(1,15):
        for name in s.RULES:
            rows=[indexed[p,h,name] for p in s.PERIODS]
            ok=all(r['fills']>=100 and r['signal_days']>=30 and r['net']['mean']>0 and r['high_net']>0 for r in rows)
            ok &= all(r['net']['lo']>0 and r['excess']['lo']>0 and r['net']['holm_p_140']<.05 and r['excess']['holm_p_140']<.05 for r in rows[1:])
            if ok:passed.append({'h':h,'rule':name})
    (HERE/'data').mkdir(exist_ok=True)
    pd.DataFrame(curves,columns=['period','h','rule','date','equity']).to_csv(HERE/'data/equity.csv',index=False)
    pd.DataFrame(samples,columns=['period','h','rule','entry','exit','sid','net']).to_csv(HERE/'data/samples.csv',index=False)
    save('results.json',{'snapshot':payload['snapshot'],'passed':passed,'results':results})
    save('manifest.json',{'snapshot_sha256':hashlib.sha256((PRIOR/'data/snapshot.pkl').read_bytes()).hexdigest(),
                          'protocol_sha256':hashlib.sha256((HERE/'PROTOCOL.md').read_bytes()).hexdigest(),
                          'snapshot_time':payload['snapshot'],'hypotheses':140,'horizons':list(range(1,15))})
    verify(payload,days,cols,filled,selections,samples,five_compare,ledger_inputs)
    report(results,passed)
    print('PASSED',passed,flush=True)


def verify(payload,days,cols,filled,selections,samples,five_compare,ledger_inputs):
    old=pd.read_csv(PRIOR/'data/trades.csv').rename(columns={'net_pct':'old_net'})
    new=pd.DataFrame(five_compare,columns=['period','rule','entry','sid','new_net'])
    merged=new.merge(old,on=['period','rule','entry','sid'],validate='one_to_one')
    assert len(merged)==len(new)
    error=float((merged.old_net-merged.new_net).abs().max());assert error<1e-10
    raw=payload['daily'].set_index(['d','sid'])
    sample=pd.DataFrame(samples,columns=['period','h','rule','entry','exit','sid','net']).groupby('h').sample(10,random_state=20261109)
    errors=[];pos={str(d):i for i,d in enumerate(days)}
    for r in sample.itertuples():
        a=pd.Timestamp(r.entry).date();b=pd.Timestamp(r.exit).date()
        expected=(raw.loc[(b,r.sid),'c']*.99705/(raw.loc[(a,r.sid),'c']*1.00095)-1)*100
        errors.append(abs(expected-r.net))
        assert pos[r.exit]-pos[r.entry]==r.h
    assert max(errors)<1e-10
    assert ledger_inputs,'No uncertainty-free ten-session ledger found'
    name='BUYBACK_QUIET' if 'BUYBACK_QUIET' in ledger_inputs else 'LOWVOL'
    loc,ts,expected=ledger_inputs[name];cash=np.full(10,.1);positions=[[] for _ in range(10)];eq=[]
    entry_set=set(ts)
    for k,t in enumerate(loc):
        d=days[t];slot=k%10
        cash[slot]+=sum(qty*float(raw.loc[(d,cols[sid]),'c'])*.99705 for sid,qty in positions[slot])
        positions[slot]=[];budget=cash[slot]/5
        if t in entry_set:
            for sid in selections[t,name]:
                price=float(raw.loc[(d,cols[sid]),'c'])
                positions[slot].append((sid,budget/(price*1.00095)));cash[slot]-=budget
        mark=sum(qty*filled[t,sid] for sleeve in positions for sid,qty in sleeve)
        eq.append(float(cash.sum()+mark))
    ledger_error=float(np.max(np.abs(expected-np.asarray(eq))));assert ledger_error<1e-10
    save('verification.json',{'prior_five_day_matched_trades':len(merged),'prior_max_error_pp':error,
                              'raw_price_samples':len(sample),'all_horizons_sampled':14,'raw_max_error_pp':max(errors),
                              'exact_exit_session_offsets':True,'independent_ledger':'check_a/'+name+'/h10',
                              'ledger_sessions':len(loc),'ledger_max_equity_error':ledger_error})
    print('Independent and prior-study reproduction checks passed.',flush=True)


def report(rows,passed):
    flat=[]
    for r in rows:
        if r['rule']=='ALL':continue
        flat.append({'period':r['period'],'h':r['h'],'rule':r['rule'],'net':r['net']['mean'],
                     'lo':r['net']['lo'],'hi':r['net']['hi'],'high_net':r['high_net'],
                     'excess':r['excess']['mean'],'account_return':r['account']['return_pct'],
                     'mdd':r['account']['mdd_pct'],'high_account_return':r['high_account']['return_pct'],
                     'fills':r['fills'],'slot_use_pct':r['slot_use_pct'],'uncertain':r['uncertain']})
    df=pd.DataFrame(flat);df.to_csv(HERE/'comparison.csv',index=False)
    index={(r['period'],r['h'],r['rule']):r for r in rows}
    lines=['# 1~14거래일 보유 비교 결과','',
           '운영 DB에서 앞서 직접 추출한 동일 스냅샷을 사용했다. 신규 140개 조합 비교이며 새 독립 OOS/Forward가 아니다. 약 2주는 10거래일이고, 14거래일은 약 3주다.', '',
           '[실행 전 계획](PROTOCOL.md) · [전체 수치](results.json) · [비교 CSV](comparison.csv) · [검증 기록](verification.json)', '',
           f'통과 조합: {passed or "없음"}. 세 구간 BASE/HIGH 양수, 확인 두 구간 Net·초과 신뢰구간 하한 양수와 Holm 140개 보정을 모두 요구했다.', '',
           '모든 기간에서 진입일을 같게 맞췄다(각 구간 마지막 14세션 신규 진입 금지). 따라서 앞선 5일 연구보다 구간 끝 9일의 진입이 적다. d 종가 → d+h 종가이며 1일은 다음 날 종가이지 다음 날 시가가 아니다.', '',
           '## 공시 + 저변동성: 보유기간 전체 비교','',
           'Net은 신호일별 거래 평균의 평균이며 계좌 주간 수익이 아니다. 계좌는 h개 자금 묶음과 현금 슬롯, 비용, 재투자를 반영한다.', '',
           '| 거래일 | 탐색 Net | 확인 A Net | 최근 Net | 최근 HIGH Net | 최근 계좌 / MDD |',
           '|---:|---:|---:|---:|---:|---:|']
    for h in range(1,15):
        a,b,c=[index[p,h,'BUYBACK_QUIET'] for p in s.PERIODS]
        lines.append(f"| {h} | {a['net']['mean']:+.3f}% | {b['net']['mean']:+.3f}% | {c['net']['mean']:+.3f}% | {c['high_net']:+.3f}% | {c['account']['return_pct']:+.2f}% / {c['account']['mdd_pct']:.2f}% |")
    lines+=['','## 10거래일: 모든 규칙','',
            '| 규칙 | 탐색 Net | 확인 A Net | 최근 Net [95%] | 최근 HIGH Net | 최근 계좌 / MDD |',
            '|---|---:|---:|---:|---:|---:|']
    for name in s.RULES+['LIQUID5']:
        a,b,c=[index[p,10,name] for p in s.PERIODS];v=c['net']
        lines.append(f"| {name} | {a['net']['mean']:+.3f}% | {b['net']['mean']:+.3f}% | {v['mean']:+.3f}% [{v['lo']:+.3f}, {v['hi']:+.3f}] | {c['high_net']:+.3f}% | {c['account']['return_pct']:+.2f}% / {c['account']['mdd_pct']:.2f}% |")
    lines+=['','## 한계','',
            '- 기간을 늘리면 거래당 비용 비중은 줄지만 자금이 더 오래 묶이고 가격 위험이 늘어난다. 거래당 평균을 단순 연환산하지 않았다.',
            '- 최근 구간은 2025-01~2026-11-06이다. 탐색은 2018-04~2022-12, 확인 A는 2023~2024다. 각 구간 계좌는 현금으로 재시작한다.',
            '- 공시 규칙은 매수 후보가 드물어 현금이 많다. 낮은 계좌 낙폭 전체를 종목 선택 능력으로 해석하지 않는다. 슬롯 사용률은 comparison.csv에 있다.',
            '- 결측 청산·기업행사 불확실은 -50% 스트레스 가상 정산이다. 0%/-100% 민감도를 남겼지만 실제 거래정지 자금은 즉시 회수할 수 없다.',
            '- 수정주가 최신본·현재 종목 분류·과거 달력 대용·공시 수신시각 미복원·종가 체결가능성 한계는 앞선 연구와 같다. 현금 배당은 가산하지 않았다.',
            '- 운영 전략·가중치·임계값·주문 기능은 변경하지 않았다. 높은 결과 한 칸을 고른 뒤 다시 튜닝하지 않았다.', '',
            '재현: `python audit/2026-11-09-holding-horizon-study/horizons.py run` (앞선 로컬 snapshot.pkl 필요).']
    (HERE/'README.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('stage',choices=['run','check','report']);args=parser.parse_args()
    if args.stage=='report':
        result=json.loads((HERE/'results.json').read_text(encoding='utf-8'))
        report(result['results'],result['passed'])
    else:
        checks() if args.stage=='check' else run()
