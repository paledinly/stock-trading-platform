"""Read-only live database audit and five-session research. See PROTOCOL.md."""
import argparse
import datetime as dt
import json
import hashlib
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
DATA = HERE / 'data'
END = dt.date(2026, 11, 8)
RULES = ['MOM20', 'REV5', 'LOWVOL', 'GAP20', 'VALUE_SURGE', 'TREND_PULLBACK',
         'QUIET_TREND', 'BALANCED', 'BUYBACK', 'BUYBACK_QUIET']
PERIODS = {'discovery': ('2018-04-01', '2022-12-31'),
           'check_a': ('2023-01-01', '2024-12-31'), 'check_b': ('2025-01-01', '2026-11-08')}
sys.path.insert(0, str(HERE.parent / '2026-11-05-investor-flow-preregistration'))
import flow_study as fs


def save(name, value):
    (HERE / name).write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str), encoding='utf-8')


def connection():
    c = fs.connect()
    c.execute('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY')
    c.execute("SET LOCAL statement_timeout = '180s'")
    return c


def audit(c):
    result = {'snapshot': c.execute('select current_timestamp').fetchone()[0],
              'read_only': c.execute('show transaction_read_only').fetchone()[0]}
    specs = [('daily_adjusted_candle', 'trade_date', 'stock_id'),
             ('delisted_daily_history', 'trade_date', 'stock_code'),
             ('investor_daily_flow', 'trade_date', 'stock_id'),
             ('dart_filing', 'rcept_dt', 'stock_code'),
             ('intraday_research_bar', 'start_time', 'stock_id')]
    for table, day, sid in specs:
        columns = [r[0] for r in c.execute("select column_name from information_schema.columns where table_schema='public' and table_name=%s", (table,))]
        result[table] = {'columns': columns}
        if day in columns and sid in columns:
            row = c.execute(f'SELECT count(*), count(distinct {sid}), min({day}), max({day}) FROM {table}').fetchone()
            result[table].update(dict(zip(['rows', 'stocks', 'first', 'last'], row)))
        print(table, {k:v for k,v in result[table].items() if k!='columns'}, flush=True)
    result['calendar'] = c.execute('select min(trade_date), max(trade_date), count(*), count(*) filter(where market_open) from trading_calendar_day').fetchone()
    result['daily_quality'] = c.execute('''select count(*) filter(where open_price<=0 or close_price<=0 or low_price<=0),
        count(*) filter(where high_price<greatest(open_price,close_price) or low_price>least(open_price,close_price) or high_price<low_price),
        count(*) filter(where volume<=0), count(*) filter(where trading_value<=0), max(fetched_at)
        from daily_adjusted_candle''').fetchone()
    result['daily_by_year'] = c.execute('''select extract(year from trade_date)::int, count(*), count(distinct stock_id), count(distinct trade_date)
        from daily_adjusted_candle group by 1 order by 1''').fetchall()
    result['daily_recent'] = c.execute('''select trade_date,count(*),count(*) filter(where volume>0)
        from daily_adjusted_candle group by 1 order by 1 desc limit 12''').fetchall()
    result['delisted'] = c.execute('select count(*),min(delisted_date),max(delisted_date) from delisted_stock').fetchone()
    save('inventory.json', result)
    print(json.dumps({k:v for k,v in result.items() if k not in [s[0] for s in specs]}, default=str), flush=True)


def extract(c):
    DATA.mkdir(exist_ok=True)
    meta = pd.DataFrame(c.execute('select id,stock_code,stock_name,is_etf,is_etn from stock').fetchall(),
                        columns=['sid','code','name','etf','etn'])
    ids = fs.static_ids(c)
    print('Loading daily prices through server cursor...', flush=True)
    daily, _ = fs.load_daily(c, ids, END)
    from sealed import load_delisted
    raw = load_delisted(c)  # protected flow fields are never used
    dm = pd.DataFrame(c.execute('select stock_code,stock_name,delisted_date from delisted_stock').fetchall(),
                      columns=['code','name','delisted_date'])
    present = set(meta.loc[meta.sid.isin(daily.sid.unique()), 'code'])
    codes = sorted(r.code for r in dm.itertuples() if fs.share(r.code, r.name) and r.code not in present)
    mapping = {code: -i-1 for i,code in enumerate(codes)}
    raw = raw[raw.stock_code.isin(mapping) & (raw.trade_date <= END)]
    dl = pd.DataFrame({'sid':raw.stock_code.map(mapping), 'd':raw.trade_date,
                       'o':raw.open_price.astype(float), 'c':raw.close_price.astype(float),
                       'v':raw.volume.astype(float), 'val':raw.trading_value.astype(float)})
    dm['sid'] = dm.code.map(mapping)
    meta = pd.concat([meta[['sid','code','name']], dm[dm.sid.notna()][['sid','code','name']]], ignore_index=True)
    meta.sid = meta.sid.astype(int)
    meta['delisted'] = meta.code.isin(dm.code)
    daily = pd.concat([daily,dl],ignore_index=True)
    cal = c.execute('select trade_date,market_open from trading_calendar_day order by trade_date').fetchall()
    # Event type and date only. No financial-report test-stage data are read.
    events = c.execute("""select stock_code,rcept_dt from dart_filing where
        (report_nm like %s or report_nm like %s)
        and report_nm not like %s and rcept_dt<=%s group by 1,2""",
        ('%자기주식취득결정%','%자기주식취득신탁계약체결결정%','[%',END)).fetchall()
    bad = c.execute('''select stock_id,trade_date from daily_adjusted_candle where
        high_price<greatest(open_price,close_price) or low_price>least(open_price,close_price) or high_price<low_price''').fetchall()
    payload = {'daily': daily, 'meta': meta, 'calendar': cal, 'events': events, 'bad': bad,
               'snapshot': str(c.execute('select current_timestamp').fetchone()[0])}
    pd.to_pickle(payload, DATA / 'snapshot.pkl')
    save('snapshot.json', {'at':payload['snapshot'], 'read_only':True, 'rows':len(daily),
                           'stocks':daily.sid.nunique(),'buyback_events':len(events),
                           'protocol_sha256':hashlib.sha256((HERE/'PROTOCOL.md').read_bytes()).hexdigest()})
    print('Snapshot cached',len(daily),'rows;',len(events),'buyback events',flush=True)


def prepare(payload, write_quality=True):
    df, meta = payload['daily'], payload['meta']
    assert not df.duplicated(['sid','d']).any(), 'duplicate stock/date'
    traded = (df.v > 0) & (df.o > 0) & (df.c > 0)
    counts = df[traded].groupby('d').size()
    empirical = set(counts[counts >= 500].index)
    official = dict(payload['calendar'])
    all_dates = sorted(d for d in set(df.d) | set(official) if min(empirical)<=d<=max(empirical))
    days = [d for d in all_dates if official.get(d, d in empirical)]
    mismatch = [str(d) for d in all_dates if d in official and official[d] != (d in empirical)]
    cols = sorted(df.sid.unique())
    def mat(name):
        return df.pivot(index='d',columns='sid',values=name).reindex(index=days,columns=cols).astype(float)
    c,o,v,val = [mat(k) for k in ['c','o','v','val']]
    valid = (v>0)&(c>0)&(o>0)
    for sid,d in payload['bad']:
        if sid in valid.columns and d in valid.index:
            valid.loc[d,sid]=False
    c, o, val = c.where(valid), o.where(valid), val.where(valid)
    delisted = meta.set_index('sid').delisted.reindex(cols).fillna(False).to_numpy()
    prev = c.ffill().shift(1)
    ret = c / prev - 1
    up = (ret > .31) | (o/prev-1 > .31)
    down = (ret < -.31) | (o/prev-1 < -.31)
    remaining = valid.iloc[::-1].cumsum().iloc[::-1] - valid.astype(int)
    jump = up | (down & ~((remaining<10) & delisted))
    val20 = val.rolling(20,min_periods=20).mean().shift(1)
    # Every feature is available before the entry session starts.
    r5 = (c/c.shift(5)-1).shift(1)
    r20 = (c/c.shift(20)-1).shift(1)
    vol = ret.rolling(20,min_periods=20).std().shift(1)
    gap = (o/prev>1).astype(float).where(c.notna()).rolling(20,min_periods=20).mean().shift(1)
    surge = (val/val.shift(1).rolling(20,min_periods=20).mean()).shift(1)
    eligible = (c.notna().rolling(60).sum().shift(1)>=60) & (val20>=1e9)
    eligible &= jump.rolling(60,min_periods=1).sum().shift(1).eq(0)
    events = pd.DataFrame(False,index=c.index,columns=c.columns)
    code_sid = meta.set_index('code').sid.to_dict()
    for code,d in payload['events']:
        sid = code_sid.get(code)
        k = int(np.searchsorted(days,d,side='right'))
        if sid in events.columns and k<len(days):
            events.iloc[k, events.columns.get_loc(sid)] = True
    rank = lambda x: x.where(eligible).rank(axis=1,pct=True,method='average')
    scores = {
        'MOM20':r20, 'REV5':-r5, 'LOWVOL':-vol, 'GAP20':gap,
        'VALUE_SURGE':surge.where(ret.shift(1)>0),
        'TREND_PULLBACK':(-r5).where((r20>0)&(r5<0)),
        'QUIET_TREND':(-vol).where(r20>0),
        'BALANCED':((rank(-vol)+rank(gap)+rank(-r5))/3).where(r20>0),
        'BUYBACK':val20.where(events),
        'BUYBACK_QUIET':val20.where(events & vol.le(vol.where(eligible).median(axis=1),axis=0)),
        'LIQUID5':val20,
    }
    quality = {'snapshot':payload['snapshot'], 'sessions':len(days),'first':str(days[0]),'last':str(days[-1]),
        'official_sessions':sum(d in official for d in days), 'proxy_sessions':sum(d not in official for d in days),
        'calendar_disagreements':mismatch,'duplicate_stock_dates':int(df.duplicated(['sid','d']).sum()),
        'bad_ohlc_rows':len(payload['bad']),'abnormal_jump_rows':int(jump.to_numpy().sum()),
        'eligible_stock_sessions':int(eligible.to_numpy().sum()),
        'valid_rows':int(valid.to_numpy().sum()),'nontrading_or_invalid_rows':int((~traded).sum()),
        'delisted_symbols_used':int(delisted.sum()),
        'recent_eligible':{str(d):int(eligible.loc[d].sum()) for d in days[-10:]}}
    # Gaps between first/last valid trading row, not pre-IPO or post-final-trade emptiness.
    inside = (valid.cumsum()>0) & (valid.iloc[::-1].cumsum().iloc[::-1]>0)
    quality['missing_or_halted_inside_history'] = int((inside & ~valid).to_numpy().sum())
    if write_quality:
        save('quality.json',quality)
        print('Prepared quality:',json.dumps(quality),flush=True)
    return days,cols,c.to_numpy(),c.ffill().to_numpy(),ret.to_numpy(),jump.to_numpy(),eligible.to_numpy(),scores


def boot(x):
    x=np.asarray(x,dtype=float)
    observed=x[np.isfinite(x)]
    if len(observed)<2:
        return {'mean':float(np.mean(observed)) if len(observed) else None,'lo':None,'hi':None,'p':1.0}
    rng=np.random.default_rng(20261109)
    means=[]
    for _ in range(50):
        starts=rng.integers(0,len(x),size=(100,(len(x)+19)//20))
        ix=(starts[:,:,None]+np.arange(20))%len(x)
        sample=x[ix.reshape(100,-1)[:,:len(x)]]
        count=np.isfinite(sample).sum(axis=1)
        means.extend(np.nansum(sample,axis=1)[count>0]/count[count>0])
    return {'mean':float(observed.mean()),'lo':float(np.quantile(means,.025)),
            'hi':float(np.quantile(means,.975)), 'p':float((np.sum(np.asarray(means)<=0)+1)/(len(means)+1))}


def paths(t, ids, c, filled, jumps, haircut=.5, buy=.00095, sell=.00295, horizon=5):
    """Mark-to-entry-capital ratios through horizon subsequent market closes (default five)."""
    if not len(ids):
        return np.empty((horizon+1,0)),np.zeros(0,dtype=bool)
    entry=c[t,ids]
    prices=filled[t:t+horizon+1,ids].copy()
    bad_jump=jumps[t+1:t+horizon+1,ids].any(axis=0)
    missing=~np.isfinite(c[t+horizon,ids])
    abnormal=bad_jump|missing
    # Quarantine paths with untrustworthy prices instead of harvesting spurious gains.
    prices[:,bad_jump]=entry[bad_jump]
    prices[-1,bad_jump]=entry[bad_jump]*(1-haircut)
    prices[-1,missing & ~bad_jump]*=(1-haircut)
    p=prices/(entry*(1+buy))
    p[-1]*=(1-sell)
    assert np.isfinite(p).all() and (p>=0).all()
    return p,abnormal


def account(cohorts, n, horizon=5):
    """Equal cash sleeves, each rolls every horizon market sessions (default five)."""
    sleeve=np.full((horizon,n),1/horizon)
    for offset in range(horizon):
        capital=1/horizon
        for t in range(offset,n,horizon):
            p=cohorts.get(t)
            if p is None:
                sleeve[offset,t:min(n,t+horizon)]=capital
                continue
            # d+5 sale and replacement share one close. p already includes both side costs.
            stop=min(n,t+horizon+1)
            sleeve[offset,t:stop]=capital*p[:stop-t]
            capital*=p[-1]
    equity=sleeve.sum(axis=0)
    peak=np.maximum.accumulate(np.r_[1.,equity])[1:]
    return {'return_pct':float((equity[-1]-1)*100),
            'mdd_pct':float(np.min(equity/peak-1)*100)},equity


def run():
    payload=pd.read_pickle(DATA/'snapshot.pkl')
    days,cols,c,filled,ret,jumps,eligible,scores=prepare(payload)
    outputs={}; all_trades=[]; curves=[]
    for pname,(start,end) in PERIODS.items():
        loc=np.array([i for i,d in enumerate(days) if start<=str(d)<=end])
        ts=loc[:-5]; base={}; base_cohorts={}
        for t in ts:
            u=np.flatnonzero(eligible[t])
            if len(u)==0:
                continue
            fill=np.isfinite(c[t,u]) & (ret[t,u]<.29) & ~jumps[t,u]
            chosen=u[fill]
            path,_=paths(t,chosen,c,filled,jumps)
            base[t]=float(np.mean(path[-1]-1)*100) if len(chosen) else 0.
            base_cohorts[t-loc[0]]=1+(path.sum(axis=1)-len(chosen))/len(u)
        basemetric,basecurve=account(base_cohorts,len(loc))
        outputs[pname]={'ALL':{'trade_day_net':boot(list(base.values())), 'account':basemetric}}
        print(pname,'ALL',outputs[pname]['ALL'],flush=True)
        for name in RULES+['LIQUID5']:
            score=scores[name].to_numpy(); daily=np.full(len(ts),np.nan); excess=np.full(len(ts),np.nan); trades=[]; trades_high=[]; highdaily=[]
            stress0=[]; stress100=[]; years={}; cohort={}; highcohort={}
            nselected=0; nfails=0; nabnormal=0; ndays=0; eventdates=[]
            for t in ts:
                if t not in base:
                    continue
                ix=np.flatnonzero(eligible[t]&np.isfinite(score[t]))
                chosen=ix[np.argsort(-score[t,ix],kind='stable')[:5]]
                nselected+=len(chosen)
                fill=np.isfinite(c[t,chosen]) & (ret[t,chosen]<.29) & ~jumps[t,chosen]
                nfails+=int((~fill).sum()); chosen=chosen[fill]
                if len(chosen)==0:
                    continue
                ndays+=1; eventdates.append(str(days[t]))
                path,abnormal=paths(t,chosen,c,filled,jumps)
                hp,_=paths(t,chosen,c,filled,jumps,buy=.00165,sell=.00365)
                z,_=paths(t,chosen,c,filled,jumps,haircut=0)
                h,_=paths(t,chosen,c,filled,jumps,haircut=1)
                vals=(path[-1]-1)*100; hv=(hp[-1]-1)*100
                nabnormal+=int(abnormal.sum())
                trades.extend(vals);trades_high.extend(hv); stress0.extend((z[-1]-1)*100);stress100.extend((h[-1]-1)*100)
                daily[t-ts[0]]=float(vals.mean());excess[t-ts[0]]=float(vals.mean()-base[t])
                highdaily.append(float(hv.mean()))
                years.setdefault(str(days[t].year),[]).append(float(vals.mean()))
                cohort[t-loc[0]]=1+(path.sum(axis=1)-len(chosen))/5
                highcohort[t-loc[0]]=1+(hp.sum(axis=1)-len(chosen))/5
                for j,sid in enumerate(chosen):
                    all_trades.append((pname,name,str(days[t]),str(days[t+5]),cols[sid],float(vals[j]),bool(abnormal[j])))
            am,eq=account(cohort,len(loc));hm,heq=account(highcohort,len(loc))
            out={'signal_days':ndays,'selected':nselected,'filled':len(trades),'unfilled':nfails,'uncertain':nabnormal,
                 'day_mean_net':boot(daily),'day_mean_excess':boot(excess),
                 'trade_mean_net':float(np.mean(trades)) if trades else None,
                 'high_trade_mean_net':float(np.mean(trades_high)) if trades else None,
                 'high_day_mean_net':float(np.mean(highdaily)) if highdaily else None,
                 'median':float(np.median(trades)) if trades else None,
                 'win_pct':float(np.mean(np.asarray(trades)>0)*100) if trades else None,
                 'cvar5':float(np.mean(np.sort(trades)[:max(1,int(len(trades)*.05))])) if trades else None,
                 'stress_no_haircut':float(np.mean(stress0)) if trades else None,
                 'stress_full_loss':float(np.mean(stress100)) if trades else None,
                 'year_day_net':{y:float(np.mean(a)) for y,a in years.items()},'account':am,'high_account':hm}
            outputs[pname][name]=out
            curves.extend((pname,name,str(days[d]),float(eq[i])) for i,d in enumerate(loc))
            print(pname,name,json.dumps(out),flush=True)
        # Holm correction across the ten actual hypotheses, separately for absolute and excess.
        for metric in ['day_mean_net','day_mean_excess']:
            ordered=sorted(RULES,key=lambda n:outputs[pname][n][metric]['p'])
            last=0.
            for j,name in enumerate(ordered):
                last=max(last,min(1.,(len(RULES)-j)*outputs[pname][name][metric]['p']))
                outputs[pname][name][metric]['holm_p']=last
    candidates=[]
    for name in RULES:
        ok=True
        for pname in PERIODS:
            r=outputs[pname][name]
            ok &= r['filled']>=100 and r['signal_days']>=30 and (r['day_mean_net']['mean'] or -999)>0 and (r['high_day_mean_net'] or -999)>0
            if pname!='discovery':
                ok &= (r['day_mean_net']['lo'] or -999)>0 and (r['day_mean_excess']['lo'] or -999)>0
                ok &= r['day_mean_net']['holm_p']<.05 and r['day_mean_excess']['holm_p']<.05
        if ok:
            candidates.append(name)
    outputs['candidates']=candidates
    save('results.json',outputs)
    pd.DataFrame(all_trades,columns=['period','rule','entry','exit','sid','net_pct','uncertain']).to_csv(DATA/'trades.csv',index=False)
    pd.DataFrame(curves,columns=['period','rule','date','equity']).to_csv(''+str(HERE/'equity.csv'),index=False)
    print('CANDIDATES:',candidates,flush=True)
    report(outputs)


def report(results):
    quality=json.loads((HERE/'quality.json').read_text(encoding='utf-8'))
    lines=['# 운영 DB 기반 5거래일 비교 결과 (2026-11-09)', '',
           '운영 전략 변경 없음. 가격·공시를 직접 읽어 실행한 신규 비교다. 기존에 여러 번 연구된 과거 데이터이므로 독립 OOS/Forward 검증은 아니다.', '',
           f"- 원자료 스냅샷: {quality['snapshot']}",
           f"- 연구 세션: {quality['first']} ~ {quality['last']}, {quality['sessions']:,}일. 공식 달력 {quality['official_sessions']}일 / 과거 거래 관측 대용 {quality['proxy_sessions']}일.",
           f"- 실제 사용 폐지 종목: {quality['delisted_symbols_used']}개. 유효 거래 행 {quality['valid_rows']:,}, 이력 내부 결측·정지 {quality['missing_or_halted_inside_history']:,}칸.",
           f"- OHLC 오류 {quality['bad_ohlc_rows']}행 제외. 비정상 점프 {quality['abnormal_jump_rows']}행: 입력 이후 60일 격리, 보유 중 발생 시 -50% 스트레스.", '',
           '[실행 전 비교 계획](PROTOCOL.md) · [DB 전체 수집 현황](inventory.json) · [분석 품질](quality.json) · [전체 결과](results.json)', '',
           '## 비교 결과', '',
           '단위 %. 거래 수는 5일 보유 건수(서로 독립 아님). Net은 신호 발생일별 동일비중 거래 평균의 평균이며, 계좌 주간 수익률이 아니다. 괄호는 날짜 블록 95% 구간. HIGH는 왕복 비용 약 0.53% 가정.', '']
    flat=[]
    def fmt(x):
        return 'NA' if x is None else f'{x:+.3f}'
    for period in PERIODS:
        lines += [f'### {period}: {PERIODS[period][0]} ~ {PERIODS[period][1]}', '',
                  '| 규칙 | 체결 / 신호일 | Net [95%] | 초과 | HIGH Net | 승률 | 계좌 누적 / MDD |',
                  '|---|---:|---:|---:|---:|---:|---:|']
        for name in RULES+['LIQUID5']:
            r=results[period][name];n=r['day_mean_net'];a=r['account']
            lines.append(f"| {name} | {r['filled']} / {r['signal_days']} | {fmt(n['mean'])} [{fmt(n['lo'])}, {fmt(n['hi'])}] | {fmt(r['day_mean_excess']['mean'])} | {fmt(r['high_day_mean_net'])} | {fmt(r['win_pct'])} | {fmt(a['return_pct'])} / {fmt(a['mdd_pct'])} |")
            flat.append({'period':period,'rule':name,'trades':r['filled'],'signal_days':r['signal_days'],
                         'net':n['mean'],'net_lo':n['lo'],'net_hi':n['hi'],
                         'excess':r['day_mean_excess']['mean'],'high_net':r['high_day_mean_net'],
                         'win_pct':r['win_pct'],'account_return':a['return_pct'],'mdd':a['mdd_pct'],
                         'uncertain':r['uncertain'],'unfilled':r['unfilled']})
        lines += ['',f"ALL 공통 대상: 5일 평균 Net {fmt(results[period]['ALL']['trade_day_net']['mean'])}%, 계좌 {fmt(results[period]['ALL']['account']['return_pct'])}%, MDD {fmt(results[period]['ALL']['account']['mdd_pct'])}%.", '']
    lines += ['## 판정', '',f"사전 고정 기준을 모두 만족한 연구 후보: {', '.join(results['candidates']) or '없음'}.", '',
              '통과는 세 구간 BASE/HIGH 양수, 확인 두 구간 절대 Net·초과 95% 하한 양수, Holm 10개 보정 통과, 구간별 100체결·30신호일을 모두 요구한다. 점수 순위 1등과 채택 가능한 전략은 다르다.', '',
              '## 해석의 한계', '',
              '- 종가 진입일 d에서 d+5까지 보유한다. 기존 공시 연구의 시가 진입·진입일 포함 다섯째 날 청산과 다른 질문이다.',
              '- 5개 독립 자금 묶음이 각각 매 5세션 교체한다. 각 묶음 안 5종목 슬롯을 동일비중으로 사용하며 미사용 슬롯은 현금이다. 수익은 재투자한다. 무제한 매일 전액 투자 방식이 아니다.',
              '- 시가/종가 일봉은 실제 체결을 보증하지 않는다. 거래대금 10억 조건과 +29% 미체결 근사는 호가 용량 검증을 대신하지 못한다.',
              '- 청산일 결측은 마지막 가격 -50%, 기업행사 불확실은 원금 -50%로 가상 정산한다. 실제 거래정지는 자금이 계속 묶인다. 0%/-100% 민감도는 results.json에 있다.',
              '- 당시의 원천 수신 이력은 복원되지 않는다. 수정주가 최신본, 현재 종목 분류, 현재 알려진 폐지 목록, 공시 제목·접수 날짜를 사용했다. 정정 전 원문·분할/병합 정합성을 완전히 검증하지 못했다.',
              '- 연도별 결과, 하위 5% 거래 평균, 미체결·불확실 건수와 다중검정 p값은 results.json에 남겼다. 계좌는 각 구간 시작에 현금 100%로 재시작한다.',
              '- 이 연구에서 새로 비교한 결합 규칙도 과거 가격을 이미 본 뒤 설계했다. 앞으로 고정 규칙의 Forward 확인 전 수익성을 검증됐다고 표현하지 않는다.', '',
              '## 재현', '', '```powershell',
              'python audit/2026-11-09-five-session-study/study.py audit',
              'python audit/2026-11-09-five-session-study/study.py extract',
              'python audit/2026-11-09-five-session-study/study.py run', '```', '',
              'data/ 원자료와 개별 거래는 Git 제외. 공개된 요약·계좌 곡선·스크립트로 계산 정의를 검토할 수 있다.']
    (HERE/'README.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    pd.DataFrame(flat).to_csv(HERE/'comparison.csv',index=False)


def selfcheck():
    c=np.full((12,2),100.); j=np.zeros_like(c,dtype=bool)
    p,bad=paths(0,np.array([0]),c,c,j)
    expected=(1-.00295)/(1+.00095)-1
    assert abs(p[-1,0]-1-expected)<1e-12
    assert not bad.any()
    cc=c.copy();cc[5,0]=np.nan
    p,bad=paths(0,np.array([0]),cc,c,j)
    assert bad[0] and p[-1,0]<.5
    j[2,0]=True
    p,bad=paths(0,np.array([0]),c,c,j,haircut=1)
    assert bad[0] and p[-1,0]==0
    am,eq=account({0:np.ones(6)},12)
    assert np.allclose(eq,1) and abs(am['mdd_pct'])<1e-10
    am,eq=account({0:np.array([1,1,1,1,1,1.1])},12)
    assert abs(eq[-1]-1.02)<1e-12
    p,_=paths(0,np.array([0]),c,c,np.zeros_like(j))
    _,eq=account({0:p[:,0],5:p[:,0]},12)
    assert abs(eq[-1]-(.8+.2*p[-1,0]**2))<1e-12
    assert boot([1.]*50)['lo']==1
    # Causality check: changes on/after a session cannot alter that session's pre-close signals.
    dates=[d.date() for d in pd.bdate_range('2024-01-02',periods=100)]
    raw=pd.DataFrame({'sid':np.tile(np.arange(500),100),'d':np.repeat(dates,500),
                      'c':np.repeat(100+np.arange(100)*.1,500),'o':np.repeat(100+np.arange(100)*.1,500),
                      'v':100_000,'val':2e9})
    meta=pd.DataFrame({'sid':np.arange(500),'code':[str(i) for i in range(500)],'delisted':False})
    payload={'daily':raw,'meta':meta,'events':[('1',dates[80])],'calendar':[],'bad':[],'snapshot':'test'}
    old=prepare(payload,False)
    raw.loc[raw.d>=dates[85],['o','c','val']]*=7
    new=prepare(payload,False)
    np.testing.assert_array_equal(old[6][:86],new[6][:86])
    for name in old[7]:
        np.testing.assert_allclose(old[7][name].to_numpy()[:86],new[7][name].to_numpy()[:86],equal_nan=True)
    print('Self-check passed: costs, exact market-session exit, missing/jump losses, sleeve accounting, bootstrap, future-data invariance.')


def verify():
    """Independent spot checks against cached source rows; explicit cash/share ledger."""
    payload=pd.read_pickle(DATA/'snapshot.pkl')
    raw=payload['daily'].set_index(['d','sid'])
    tx=pd.read_csv(DATA/'trades.csv')
    plain=tx[~tx.uncertain].sample(100,random_state=20261109)
    # Use the exported market calendar, not per-stock row offsets.
    ec=pd.read_csv(HERE/'equity.csv')
    calendars={p:sorted(g.date.unique()) for p,g in ec.groupby('period')}
    errors=[]
    for r in plain.itertuples():
        a=dt.date.fromisoformat(r.entry);b=dt.date.fromisoformat(r.exit)
        price_a=float(raw.loc[(a,r.sid),'c']);price_b=float(raw.loc[(b,r.sid),'c'])
        expected=100*(price_b*.99705/(price_a*1.00095)-1)
        errors.append(abs(expected-r.net_pct))
        days=calendars[r.period]
        assert days.index(r.exit)-days.index(r.entry)==5
    assert max(errors)<1e-9
    # BUYBACK_QUIET check A has no synthetic uncertainty; compare every mark against a cash/share ledger.
    period,rule='check_a','BUYBACK_QUIET'
    subset=tx[(tx.period==period)&(tx.rule==rule)]
    assert not subset.uncertain.any()
    days=calendars[period]; cash=np.full(5,.2); positions=[[] for _ in range(5)]; equity=[]
    by_entry={d:g for d,g in subset.groupby('entry')}
    latest={}
    for k,date in enumerate(days):
        d=dt.date.fromisoformat(date)
        # Update marks for all open positions. No selection or signal routine is reused.
        for sleeve in positions:
            for sid,qty in sleeve:
                if (d,sid) in raw.index:
                    row=raw.loc[(d,sid)]
                    if row.v>0:
                        latest[sid]=float(row.c)
        slot=k%5
        cash[slot]+=sum(qty*latest[sid]*.99705 for sid,qty in positions[slot])
        positions[slot]=[]
        budget=cash[slot]/5
        if date in by_entry:
            for r in by_entry[date].itertuples():
                price=float(raw.loc[(d,r.sid),'c'])
                qty=budget/(price*1.00095)
                cash[slot]-=budget
                latest[r.sid]=price
                positions[slot].append((r.sid,qty))
        equity.append(float(cash.sum()+sum(qty*latest[sid] for sleeve in positions for sid,qty in sleeve)))
    expected=ec[(ec.period==period)&(ec.rule==rule)].equity.to_numpy()
    delta=float(np.max(np.abs(np.asarray(equity)-expected)))
    assert delta<1e-9,(delta,equity[-1],expected[-1])
    save('verification.json',{'sampled_trades':100,'max_return_error_pp':max(errors),
                              'all_sample_exits_exactly_five_market_sessions':True,
                              'independent_ledger':period+'/'+rule,'ledger_sessions':len(days),
                              'ledger_max_equity_difference':delta,'future_data_invariance':'passed in check'})
    print('Independent verification passed:',max(errors),delta,flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('stage', choices=['audit','extract','run','check','verify'])
    args = parser.parse_args()
    if args.stage=='check':
        selfcheck()
    elif args.stage=='run':
        selfcheck();run()
    elif args.stage=='verify':
        selfcheck();verify()
    else:
        with connection() as conn:
            (audit if args.stage=='audit' else extract)(conn)
