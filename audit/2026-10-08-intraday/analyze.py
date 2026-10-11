"""Observed-path diagnosis only; never edits strategy, DB, or stored outcomes."""
from pathlib import Path
import csv, json, math, collections
import numpy as np
import pandas as pd

ROOT = Path(__file__).parent
DATE = '2026-10-08'
def read(name):
    return list(csv.DictReader((ROOT/name).open(encoding='utf-8'), delimiter='\t'))
def ts(value):
    return pd.Timestamp(value).timestamp() if value else None
def local(value):
    if value is None or pd.isna(value): return None
    return pd.Timestamp(value, unit='s', tz='UTC').tz_convert('Asia/Seoul').strftime('%H:%M:%S.%f')[:-3]
def pct(price, base): return (price/base-1)*100 if base and price is not None else None
def clean(value):
    if isinstance(value, (np.integer,)): return int(value)
    if isinstance(value, (float,np.floating)) and not math.isfinite(value): return None
    if isinstance(value, (dict,list,tuple)): return json.dumps(value,ensure_ascii=False)
    return value
def save(name,rows):
    if not rows: return
    fields=list(dict.fromkeys(k for row in rows for k in row))
    with (ROOT/name).open('w',encoding='utf-8-sig',newline='') as f:
        w=csv.DictWriter(f,fieldnames=fields);w.writeheader()
        for row in rows: w.writerow({k:clean(v) for k,v in row.items()})
records=read('recommendations.tsv')
for r in records: r['s']=json.loads(r['snapshot']);r['o']=json.loads(r['outcome'])
today=[r for r in records if r['session_date']==DATE]
snapshot=read('metadata.tsv')[0]['snapshot_at']
frames=[]
for path in sorted(ROOT.glob('ticks-*.tsv')):
    f=pd.read_csv(path,sep='\t',dtype={'stock_code':str})
    for col in ['source_at','received_at','evaluated_at']:
        f[col]=pd.to_datetime(f[col],utc=True,format='mixed').astype('datetime64[ns, UTC]').astype('int64')/1e9
    for col in ['price','volume','value','strength','day_high','day_low','vwap','buy_volume','sell_volume']:
        f[col]=pd.to_numeric(f[col],errors='coerce')
    frames.append(f)
ticks=pd.concat(frames,ignore_index=True).sort_values('id')
assert ticks.id.is_unique, 'Duplicate export pages'
assert pd.Timestamp(ticks.source_at.max(),unit='s',tz='UTC').tz_convert('Asia/Seoul').strftime('%Y-%m-%d')==DATE
assert abs(float((ticks.received_at-ticks.source_at).median()))<60, 'Timestamp unit mismatch'
bycode={code:g.sort_values(['source_at','id']) for code,g in ticks.groupby('stock_code')}
invalid_clock=(ticks.source_at>ticks.received_at+0.000001)|(ticks.received_at>ticks.evaluated_at+0.000001)
def ordered_observations(g):
    g=g[(g.source_at<=g.received_at+0.000001)&(g.received_at<=g.evaluated_at+0.000001)].sort_values('id')
    keep=[];last_time=None;last_volume=-1
    for r in g.itertuples():
        if last_time is not None and (r.source_at<last_time or (r.source_at==last_time and r.volume<=last_volume)):continue
        keep.append(r.Index);last_time=r.source_at;last_volume=r.volume
    return g.loc[keep].sort_values(['source_at','id'])
bycode={code:ordered_observations(g) for code,g in bycode.items()}
minutes=read('minutes.tsv'); minute_by=collections.defaultdict(list)
for row in minutes:
    m=json.loads(row['payload']);m['time']=ts(row['start_time']);m['final']=ts(row['finalized_at']);minute_by[row['symbol']].append(m)
contexts=[json.loads(r['payload']) for r in read('contexts.tsv')]
daily=collections.defaultdict(list)
for row in read('daily.tsv'): daily[row['stock_code']].append(row)

def segment(g,a,b): return g[(g.source_at>=a)&(g.source_at<b)]
def base(g,t,max_age=60):
    rows=g[g.source_at<=t]
    if rows.empty:return None
    row=rows.iloc[-1]
    return row if t-row.source_at<=max_age else None
def follow(g,t,tolerance=10):
    rows=g[(g.source_at>=t)&(g.source_at<=t+tolerance)]
    if rows.empty:return None
    row=rows.iloc[0]
    if row.data_loss or row.received_at-row.source_at>10 or row.evaluated_at-row.received_at>10:return None
    return row
def gaps(g):
    if g.empty:return 0
    return int((g.source_at.diff()>60).sum()+g.data_loss.fillna(False).astype(bool).sum()
               +(g.received_at-g.source_at>10).sum()+(g.evaluated_at-g.received_at>10).sum())
def flow(g,t,n):
    a=base(g,t-n*60);b=base(g,t)
    if a is None or b is None:return None,None
    vol=b.volume-a.volume;money=b.value-a.value
    return (float(vol) if vol>=0 else None,float(money) if money>=0 else None)
def corr(rows,x,y):
    pairs=[(r.get(x),r.get(y)) for r in rows if r.get(x) is not None and r.get(y) is not None
           and math.isfinite(r[x]) and math.isfinite(r[y])]
    if len(pairs)<3:return len(pairs),None
    a=np.array(pairs)
    if np.std(a[:,0])==0 or np.std(a[:,1])==0:return len(pairs),None
    return len(pairs),float(np.corrcoef(a.T)[0,1])

result=[];windows=[];markets=[];latencies=[]
for r in today:
    s,o=r['s'],r['o'];code=s['stockCode'];g=bycode.get(code,pd.DataFrame());t=ts(s['recommendedAt']);source=ts(s['sourceAt']);v=s['features']['values'];plan=s['plan'];entry=o.get('entryPrice');entryat=ts(o.get('entryAt'));exit_at=ts(o.get('exitAt'))
    # DB timestamptz rounds nanosecond Java Instants to microseconds.
    available=g[(g.source_at<=source)&(g.received_at<=t+0.000001)&(g.evaluated_at<=t+0.000001)]
    exact=available[abs(available.evaluated_at-t)<0.00001]
    signalrow=exact.iloc[-1] if len(exact) else base(available,source,0)
    post=segment(g,t,t+3600.001);entrypost=post[post.received_at>=entryat-0.000001] if entryat else post.iloc[:0]
    holding=entrypost[entrypost.received_at<=exit_at+0.000001] if exit_at else entrypost
    # A fragmented multi-tick callback can share one receipt timestamp. Stop at
    # the first threshold tick, rather than including later ticks in that frame.
    hit=holding[holding.price<=plan['stop']] if o['status']=='STOPPED' else (
        holding[holding.price>=plan['target1']] if o['status']=='TARGET_HIT' else holding.iloc[:0])
    if len(hit): holding=holding[holding.id<=hit.iloc[0].id]
    row={'id':s['id'],'code':code,'name':s['stockName'],'time_KST':local(t),'source_KST':local(source),
         'current_master_type':r['market_type'],'status':o['status'],'setup':s['setup'],'score':s['score'],
         'grade':None,'signal_price':s['price'],'entry_price':entry,'entry_KST':local(entryat),'entry_from':plan['entryFrom'],'entry_to':plan['entryTo'],
         'stop':plan['stop'],'target1':plan['target1'],'target2':plan['target2'],'planned_RR':plan['riskReward'],
         'stop_pct':pct(plan['stop'],entry),'target_pct':pct(plan['target1'],entry),
         'actual_RR':(plan['target1']-entry)/(entry-plan['stop']) if entry and entry>plan['stop'] else None,
         'entry_deviation_pct':pct(entry,s['price']),'stop_time_KST':local(exit_at) if o['status']=='STOPPED' else None,
         'exit_KST':local(exit_at),'exit_price':o.get('exitPrice'),'gross_return':o.get('grossReturn'),'net_return':o.get('netReturn'),
         'target_first':o.get('targetBeforeStop'),'holding_minutes':o.get('holdingMinutes'),
         'trade_MFE':o.get('tradeMfe'),'trade_MAE':o.get('tradeMae'),'signal_MFE_to_snapshot':o.get('mfe'),'signal_MAE_to_snapshot':o.get('mae'),
         'signal_post60_high':float(post.price.max()) if len(post) else None,'signal_post60_low':float(post.price.min()) if len(post) else None,
         'entry_post60_MFE':max(0,pct(float(entrypost.price.max()),entry)) if entry and len(entrypost) else None,
         'entry_post60_MAE':min(0,pct(float(entrypost.price.min()),entry)) if entry and len(entrypost) else None,
         'entry_post60_high':float(entrypost.price.max()) if len(entrypost) else None,'entry_post60_low':float(entrypost.price.min()) if len(entrypost) else None,
         'holding_path_MFE':max(0,pct(float(holding.price.max()),entry)) if entry and len(holding) else None,
         'holding_path_MAE':min(0,pct(float(holding.price.min()),entry)) if entry and len(holding) else None,
         'vwap':v.get('vwap'),'vwap_distance_pct':v.get('vwapDistance'),'strength':v.get('strength'),
         'daily_turnover':v.get('dailyValue'),'daily_volume':float(signalrow.volume) if signalrow is not None else None,
         'day_high':float(signalrow.day_high) if signalrow is not None else None,'day_low':float(signalrow.day_low) if signalrow is not None else None,
         'prior_high':v.get('setupResistance'),'support':v.get('setupSupport'),'setup_range':v.get('setupRange'),
         'reasons':s['reasons'],'risks':s['risks'],'quality':o.get('quality',[]),'uncertain':o.get('uncertain'),
         'costs_configured':all(x is not None for x in s['policy']['costs'].values()),'post60_gap_events':gaps(post),
         'post60_observed_minutes':(float(post.source_at.max())-t)/60 if len(post) else None,
         'market_regime':'UNAVAILABLE','sector':'UNAVAILABLE','theme':'UNAVAILABLE'}
    if row['day_high'] and row['day_low'] and row['day_high']>row['day_low']:
        row['high_position']=(s['price']-row['day_low'])/(row['day_high']-row['day_low'])
        row['distance_from_high_pct']=(row['day_high']-s['price'])/row['day_high']*100
    latest=daily.get(code,[])[-1] if daily.get(code) else None
    row['prior_daily_date']=pd.Timestamp(latest['start_time']).tz_convert('Asia/Seoul').strftime('%Y-%m-%d') if latest else None
    row['day_return_pct']=pct(s['price'],float(latest['close'])) if latest and row['prior_daily_date']=='2026-10-07' and ts(latest['created_at'])<=t else None
    row['daily_close_known_at_signal']=latest is not None and ts(latest['created_at'])<=t
    recovery=segment(available,source-300,source+.001)
    crosses=recovery[recovery.price>v.get('setupResistance',float('inf'))]
    row['first_breakout_cross_to_signal_s']=t-float(crosses.source_at.iloc[0]) if len(crosses) else None
    below=recovery[recovery.price<=v.get('setupResistance',float('-inf'))]
    reclaim=crosses[crosses.source_at>below.source_at.iloc[-1]] if len(below) else crosses
    row['last_reclaim_to_signal_s']=t-float(reclaim.source_at.iloc[0]) if len(reclaim) else None
    row['holding_gap_events']=gaps(holding)
    for n in [1,3,5,10,20,30]:
        f=follow(g,t+n*60)
        row[f'return_{n}m']=pct(float(f.price),s['price']) if f is not None else None
        row[f'return_{n}m_observed_KST']=local(f.source_at) if f is not None else None
        pre=base(available,source-n*60)
        row[f'pre_return_{n}m']=pct(s['price'],float(pre.price)) if pre is not None else None
        vol,money=flow(available,source,n);pvol,pmoney=flow(available,source-n*60,n)
        row[f'volume_{n}m']=vol;row[f'turnover_{n}m']=money
        row[f'volume_ratio_{n}m']=vol/pvol if vol is not None and pvol and pvol>0 else None
        row[f'turnover_ratio_{n}m']=money/pmoney if money is not None and pmoney and pmoney>0 else None
        row[f'strength_{n}m_ago']=float(pre.strength) if pre is not None and pd.notna(pre.strength) else None
        row[f'strength_change_{n}m']=v.get('strength')-row[f'strength_{n}m_ago'] if v.get('strength') is not None and row[f'strength_{n}m_ago'] is not None else None
    for a,b,label in [(t-1800,t,'pre30'),(t-900,t,'pre15'),(t-300,t,'pre5')]+[(t,t+n*60,f'post{n}') for n in [1,3,5,10,20,30]]:
        z=segment(available if label.startswith('pre') else g,a,b)
        windows.append({'id':s['id'],'name':s['stockName'],'time_KST':row['time_KST'],'window':label,'ticks':len(z),'open':float(z.price.iloc[0]) if len(z) else None,'high':float(z.price.max()) if len(z) else None,'low':float(z.price.min()) if len(z) else None,'close':float(z.price.iloc[-1]) if len(z) else None,'volume_delta':float(z.volume.iloc[-1]-z.volume.iloc[0]) if len(z)>1 else None,'turnover_delta':float(z.value.iloc[-1]-z.value.iloc[0]) if len(z)>1 else None,'gap_events':gaps(z),'observed_span_s':float(z.source_at.iloc[-1]-z.source_at.iloc[0]) if len(z)>1 else None})
    minrows=[m for m in minute_by[code] if m['time']<t and m['final']<=t][-31:]
    last6=minrows[-6:]
    row['last6_finalized_minute_volume']=[float(m['volume']) for m in last6]
    row['last6_finalized_minute_turnover']=[float(m['turnover']) for m in last6]
    row['last6_finalized_minute_KST']=[local(m['time']) for m in last6]
    row['last6_minutes_complete']=bool(len(last6)==6 and all(m.get('complete',False) for m in last6)
                                      and max(np.diff([m['time'] for m in last6]))<=60)
    if row['last6_minutes_complete']:
        row['last_minute_volume_decelerating']=float(last6[-1]['volume'])<float(last6[-2]['volume'])
        row['last_minute_turnover_decelerating']=float(last6[-1]['turnover'])<float(last6[-2]['turnover'])
    if len(minrows)>=15:
        closes=np.array([float(m['close']) for m in minrows]);highs=np.array([float(m['high']) for m in minrows]);lows=np.array([float(m['low']) for m in minrows]);diff=np.diff(closes)[-14:]
        gain=np.maximum(diff,0).mean();loss=-np.minimum(diff,0).mean()
        row['RSI14_simple_1m']=100 if loss==0 else 100-100/(1+gain/loss)
        tr=np.maximum(highs[1:]-lows[1:],np.maximum(abs(highs[1:]-closes[:-1]),abs(lows[1:]-closes[:-1])))
        row['ATR14_simple_1m']=float(tr[-14:].mean());row['stop_to_ATR']=abs(entry-plan['stop'])/row['ATR14_simple_1m'] if entry and row['ATR14_simple_1m']>0 else None
        row['MA20_distance_pct']=pct(s['price'],float(closes[-20:].mean())) if len(closes)>=20 else None
        row['minute_count_for_indicators']=len(minrows)
        row['indicator_max_gap_s']=max(np.diff([m['time'] for m in minrows]))
    for n in [1,3,5,10]:
        flowmin=[m for m in minrows if source-n*60<=m['time']<source]
        row[f'finalized_minute_volume_{n}m']=sum(float(m['volume']) for m in flowmin) if flowmin else None
    if len(post):
        peak=post.loc[post.price.idxmax()];row['post60_peak_after_min']=(peak.source_at-t)/60
        early=segment(g,t,t+300)
        row['first5_peak_after_min']=(early.loc[early.price.idxmax()].source_at-t)/60 if len(early) else None
        first=follow(g,t)
        row['first_observed_return']=pct(float(first.price),s['price']) if first is not None else None
    afterstop=segment(g,exit_at, min(exit_at+1800,t+3600.001)) if exit_at and o['status']=='STOPPED' else g.iloc[:0]
    recovered=afterstop[afterstop.price>=plan['target1']]
    stop_window_complete=bool(len(afterstop) and exit_at and float(afterstop.source_at.max())>=exit_at+1790
                              and gaps(afterstop)==0)
    row['post_stop_target_within30m']=True if len(recovered) else (False if stop_window_complete else None)
    row['post_stop_window_complete']=stop_window_complete
    row['post_stop_gap_events']=gaps(afterstop)
    row['post_stop_target_first_KST']=local(recovered.source_at.iloc[0]) if len(recovered) else None
    row['post_stop_max_return']=pct(float(afterstop.price.max()),entry) if entry and len(afterstop) else None
    row['post_stop_min_return']=pct(float(afterstop.price.min()),entry) if entry and len(afterstop) else None
    if o['status']=='UNRESOLVED':category='F: 관측 공백·판정 불가'
    elif entry is None:category='G: 미진입·무효'
    elif o['status']=='TARGET_HIT':category='H: 목표 선도달'
    elif o['status']=='ENTERED':category='I: 진행 중'
    elif row['post_stop_target_within30m']:category='C: 손절 후 30분 내 목표 재도달(관측상)'
    elif (o.get('tradeMfe') or 0)==0:category='A: 보유 중 유리한 움직임 없이 손절'
    else:category='B: 상승 여유 일부 발생 후 목표 미달·손절'
    row['category']=category
    row['D_candidate']=bool(row.get('first5_peak_after_min') is not None and row['first5_peak_after_min']<=1 and (row.get('return_5m') or 0)<0)
    latency={'id':s['id'],'name':s['stockName'],'time_KST':row['time_KST'],'source_to_receipt_s':ts(s['receivedAt'])-source,'receipt_to_evaluation_s':t-ts(s['receivedAt']),'source_to_evaluation_s':t-source,'signal_to_entry_s':entryat-t if entryat else None,'db_saved_at':None,'scanner_execution_at':None,'indicator_calculated_at':None,'signal_commit_at':None,'ui_display_at':None,'ui_poll_interval_s':5}
    latencies.append(latency)
    market={'id':s['id'],'name':s['stockName'],'time_KST':row['time_KST'],'KOSPI':None,'KOSDAQ':None,'KOSPI200':None,'index_5m':None,'index_15m':None,'breadth_up':None,'breadth_down':None,'market_turnover':None,'VI_count':None,'declining_fraction':None,'sector':None,'theme':None,'regime':'UNAVAILABLE'}
    for kind in ['FOREIGN_NET_BUY','INSTITUTION_NET_BUY','PROGRAM_NET_BUY']:
        ctx=[c for c in contexts if c['symbol']==code and c['kind']==kind and ts(c['receivedAt'])<=t]
        c=ctx[-1] if ctx else None
        market[kind]=c['value'] if c else None;market[kind+'_age_s']=t-ts(c['receivedAt']) if c else None
        market[kind+'_unit']=c.get('unit') if c else None;market[kind+'_scope']=c.get('scope') if c else None
        market[kind+'_status']=c.get('status') if c else None
    markets.append(market);result.append(row)
save('recommendations_full.csv',result);save('price_windows.csv',windows);save('latency.csv',latencies);save('market_context.csv',markets)

def summary(rows):
    states=collections.Counter(r['o']['status'] for r in rows);closed=[r for r in rows if r['o'].get('grossReturn') is not None and not r['o'].get('uncertain')];net=[r for r in closed if r['o'].get('netReturn') is not None]
    gross=[r['o']['grossReturn'] for r in closed];netvals=[r['o']['netReturn'] for r in net];entered=[r for r in rows if r['o'].get('entryPrice') is not None];decided=[r for r in rows if r['o'].get('targetBeforeStop') is not None and not r['o'].get('uncertain')]
    mfe=[r['o']['tradeMfe'] for r in closed if r['o'].get('tradeMfe') is not None];mae=[r['o']['tradeMae'] for r in closed if r['o'].get('tradeMae') is not None]
    ordered=sorted(closed,key=lambda r:r['o']['exitAt']);streak=maximum=0
    for r in ordered:streak=streak+1 if r['o']['grossReturn']<0 else 0;maximum=max(maximum,streak)
    gains=sum(max(v,0) for v in gross);losses=-sum(min(v,0) for v in gross)
    return {'recommendations':len(rows),'entered':len(entered),'states':dict(states),'closed_valid':len(closed),'gross_win_rate':sum(v>0 for v in gross)/len(gross)*100 if gross else None,'gross_mean':float(np.mean(gross)) if gross else None,'gross_PF':gains/losses if losses else None,'gross_avg_win':float(np.mean([v for v in gross if v>0])) if any(v>0 for v in gross) else None,'gross_avg_loss':float(np.mean([v for v in gross if v<0])) if any(v<0 for v in gross) else None,'net_samples':len(net),'net_mean':float(np.mean(netvals)) if netvals else None,'MFE_mean':float(np.mean(mfe)) if mfe else None,'MFE_median':float(np.median(mfe)) if mfe else None,'MAE_mean':float(np.mean(mae)) if mae else None,'MAE_median':float(np.median(mae)) if mae else None,'max_loss_streak':maximum,'first_hit_samples':len(decided),'target_first_rate':sum(r['o']['targetBeforeStop'] for r in decided)/len(decided)*100 if decided else None,'stop_first_rate':sum(not r['o']['targetBeforeStop'] for r in decided)/len(decided)*100 if decided else None}
history=[]
for day in sorted(set(r['session_date'] for r in records)):
    rows=[r for r in records if r['session_date']==day];history.append({'date':day,**summary(rows),'policy_count':len(set(json.dumps(r['s']['policy'],sort_keys=True) for r in rows))})
save('history_daily.csv',history)
bands=[]
for lo,hi in [(0,60),(60,70),(70,80),(80,90),(90,101)]:
    rows=[r for r in today if lo<=r['s']['score']<hi];bands.append({'score_band':f'{lo}–{hi-1}',**summary(rows)})
save('score_bands.csv',bands)
features=['score','volume_ratio_1m','volume_ratio_3m','volume_ratio_5m','turnover_ratio_1m','turnover_ratio_3m','turnover_ratio_5m','vwap_distance_pct','strength','strength_change_1m','strength_change_3m','strength_change_5m','pre_return_5m','day_return_pct','high_position','stop_to_ATR','stop_pct','target_pct']
correlations=[]
for x in features:
    for y in ['trade_MFE','trade_MAE','gross_return','entry_post60_MFE']:
        n,value=corr([r for r in result if not r['uncertain']],x,y);correlations.append({'feature':x,'outcome':y,'n':n,'pearson':value})
for x,y in [('volume_ratio_5m','turnover_ratio_5m'),('volume_ratio_5m','pre_return_5m'),('turnover_ratio_5m','pre_return_5m')]:
    n,value=corr(result,x,y);correlations.append({'feature':x,'outcome':y,'n':n,'pearson':value})
save('correlations.csv',correlations)
stats={'snapshot_at':snapshot,'today':summary(today),'equity_today':summary([r for r in today if r['market_type']=='STOCK']),'ETF_today':summary([r for r in today if r['market_type']=='ETF']),
       'history':history,'tick_rows':len(ticks),'tick_symbols':len(bycode),'tick_last':local(float(ticks.source_at.max())),
       'categories':dict(collections.Counter(r['category'] for r in result)),
       'observed_stop_recovery':sum(r['post_stop_target_within30m'] is True for r in result),
       'costs_configured_today':sum(r['costs_configured'] for r in result),
       'source_to_eval_mean_s':float(np.mean([r['source_to_evaluation_s'] for r in latencies])),
       'source_to_eval_max_s':max(r['source_to_evaluation_s'] for r in latencies),
       'source_to_eval_p95_s':float(np.quantile([r['source_to_evaluation_s'] for r in latencies],.95)),
       'post60_gap_recommendations':sum(r['post60_gap_events']>0 for r in result),
       'policy_count_today':len(set(json.dumps(r['s']['policy'],sort_keys=True) for r in today)),
       'under1_ATR':sum(r.get('stop_to_ATR') is not None and r['stop_to_ATR']<1 for r in result),
       'indices_recorded':sum(c['kind'].startswith('INDEX') for c in contexts)}
complete30=[]
for r in result:
    t=ts(next(x['s']['recommendedAt'] for x in today if x['s']['id']==r['id']));z=segment(bycode[r['code']],t,t+1800.001)
    if len(z) and z.source_at.max()>=t+1790 and gaps(z)==0:
        peak_time=float(z.loc[z.price.idxmax()].source_at) if z.price.max()>r['signal_price'] else t
        complete30.append(peak_time-t<=300)
stats['invalid_clock_input_rows']=int(invalid_clock.sum())
stats['ordered_usable_tick_rows']=sum(len(g) for g in bycode.values())
for metric in ['MFE','MAE']:
    differences=[abs(r['holding_path_'+metric]-r['trade_'+metric]) for r in result
                 if r['gross_return'] is not None and not r['uncertain'] and r['holding_path_'+metric] is not None]
    stats['holding_'+metric+'_max_reconstruction_difference']=max(differences) if differences else None
    assert not differences or max(differences)<1e-8, 'Holding excursion disagrees with recorded first-hit path'
stats['immediate_decline_samples']=sum(r.get('first_observed_return') is not None for r in result)
stats['immediate_decline_count']=sum(r.get('first_observed_return') is not None and r['first_observed_return']<0 for r in result)
stats['one_minute_decline_samples']=sum(r.get('return_1m') is not None for r in result)
stats['one_minute_decline_count']=sum(r.get('return_1m') is not None and r['return_1m']<0 for r in result)
stats['peak_within5m_complete30_samples']=len(complete30)
stats['peak_within5m_count']=sum(complete30)
(ROOT/'summary.json').write_text(json.dumps(stats,ensure_ascii=False,indent=2),encoding='utf-8')
assert abs(pct(10180,10000)-1.8)<1e-10 and abs(pct(9830,10000)+1.7)<1e-10
assert len(result)==len(today)
print(json.dumps(stats,ensure_ascii=False,indent=2))
print(pd.DataFrame(result)[['name','time_KST','status','score','trade_MFE','trade_MAE','stop_pct','target_pct','stop_to_ATR','return_5m','return_20m','post_stop_target_within30m','category']].to_string(index=False))
