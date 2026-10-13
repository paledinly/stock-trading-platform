import json,sys,csv,glob,statistics as st,collections
SCR=sys.argv[1]
etf={r['stock_code']:r['is_etf'] in('t','true') for r in csv.DictReader(open('audit/2026-10-12-intraday-net/recs.tsv',encoding='utf-8'),delimiter='\t')}
rows=[]
for p in sorted(glob.glob(SCR+'/out/*.json')):
    r=json.load(open(p,encoding='utf-8'))
    for x in r['comparisons']['BASELINE']['results']:
        s,o=x['signal'],x['outcome']
        if etf.get(s['stockCode']): continue
        if not(o['exitAt'] and o['grossReturn'] is not None and not o['uncertain']): continue
        rows.append((s,o))
print('n',len(rows))
def show(title,key,edges):
    print('\n##',title)
    b=collections.defaultdict(list)
    for s,o in rows:
        v=key(s,o)
        if v is None: continue
        lab=next((f'<{e}' for e in edges if v<e),f'>={edges[-1]}')
        b[lab].append(o)
    for lab in [f'<{e}' for e in edges]+[f'>={edges[-1]}']:
        os_=b.get(lab,[])
        if not os_: continue
        g=[o['grossReturn'] for o in os_]; t=[o for o in os_ if o['targetBeforeStop'] is not None]
        print(f'{lab:>10} n={len(os_):3d} gross={st.mean(g):+.3f} med={st.median(g):+.3f} targetFirst={100*sum(o["targetBeforeStop"] for o in t)/max(1,len(t)):5.1f}% mfe={st.mean(o["tradeMfe"] for o in os_):.2f} mae={st.mean(o["tradeMae"] for o in os_):.2f}')
f=lambda k:(lambda s,o:s['features']['values'].get(k))
show('stop distance % (entry->stop)',lambda s,o:(o['entryReferencePrice']-s['plan']['stop'])/o['entryReferencePrice']*100,[0.2,0.4,0.7,1.0])
show('return5m at signal %',f('return5m'),[0.5,1,2,3])
show('return15m %',f('return15m'),[0.5,1,2,4])
show('vwapDistance %',f('vwapDistance'),[0.5,1,2,3])
show('highDistance % (to day high)',f('highDistance'),[-3,-1.5,-0.5,-0.01])
show('volumeRatio5m',f('volumeRatio5m'),[2,3,5])
show('strength',f('strength'),[80,100,130])
show('momentumAcceleration',f('momentumAcceleration'),[-0.5,0,0.5])
show('dailyValue (100M KRW)',lambda s,o:s['features']['values'].get('dailyValue',0)/1e8,[50,200,1000])
show('score',lambda s,o:s['score'],[60,65,70])
print('\n## session'); 
for k,v in collections.Counter(s['session'] for s,o in rows).items():
    g=[o['grossReturn'] for s,o in rows if s['session']==k]; print(k,len(g),f'{st.mean(g):+.3f}')
print('\n## setup')
for k,v in collections.Counter(s['setup'] for s,o in rows).items():
    g=[o['grossReturn'] for s,o in rows if s['setup']==k]; print(k,len(g),f'{st.mean(g):+.3f}')
print('\n## exit status'); print(collections.Counter(o['status'] for s,o in rows))
print('holding min median', st.median(o['holdingMinutes'] for s,o in rows))
print('tradeMfe median', st.median(o['tradeMfe'] for s,o in rows), 'share mfe<=0', sum(o['tradeMfe']<=0 for s,o in rows)/len(rows))
print('target dist % median', st.median((s['plan']['target1']-o['entryReferencePrice'])/o['entryReferencePrice']*100 for s,o in rows))
print('signal->entry sec median', st.median((__import__('datetime').datetime.fromisoformat(o['entryAt'].replace('Z','+00:00'))-__import__('datetime').datetime.fromisoformat(s['recommendedAt'].replace('Z','+00:00'))).total_seconds() for s,o in rows))
print('entry vs signal price % median', st.median((o['entryReferencePrice']/s['price']-1)*100 for s,o in rows))
print('repeat symbols', collections.Counter(s['stockCode'] for s,o in rows).most_common(6))
