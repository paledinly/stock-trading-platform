import sys,collections,statistics as st,random
from datetime import datetime,timedelta,timezone
SCR=sys.argv[1]
P=lambda s:datetime.fromisoformat(s.replace('Z','+00:00'))
bars=collections.defaultdict(list)
for l in open(SCR+'/bars.tsv',encoding='utf-8'):
    c,d,t,o,h,lo,cl=l.rstrip('\n').split('\t'); bars[(c,d)].append((P(t),float(o),float(h),float(lo),float(cl)))
KST=timezone(timedelta(hours=9))
def at(series,t):  # close of last bar that has fully closed by t (no look-ahead)
    done=[b for b in series if b[0]+timedelta(minutes=5)<=t]
    return done[-1][4] if done else None
H=[5,15,30,60,120]
rec=[]
for l in open(SCR+'/recs_for_path.tsv',encoding='utf-8'):
    i,c,t,p=l.rstrip('\n').split('\t'); t=P(t); p=float(p); d=t.astimezone(KST).date().isoformat()
    s=bars.get((c,d))
    if not s: continue
    row={'h':{}, 'pre30':None}
    for h in H:
        v=at(s,t+timedelta(minutes=h))
        if v and t+timedelta(minutes=h)<=s[-1][0]+timedelta(minutes=5): row['h'][h]=(v/p-1)*100
    row['close']=(s[-1][4]/p-1)*100
    pre=at(s,t-timedelta(minutes=30)); 
    if pre: row['pre30']=(p/pre-1)*100
    w=[b for b in s if t<=b[0]+timedelta(minutes=5) and b[0]<t+timedelta(minutes=60)]
    if w: row['up60']=(max(b[2] for b in w)/p-1)*100; row['dn60']=(min(b[3] for b in w)/p-1)*100
    rec.append(row)
print('recs with bars',len(rec))
for h in H:
    v=[r['h'][h] for r in rec if h in r['h']]
    print(f'+{h:>3}m n={len(v):3d} mean={st.mean(v):+.3f}% median={st.median(v):+.3f}% up-share={100*sum(x>0 for x in v)/len(v):.0f}%')
v=[r['close'] for r in rec]; print(f'close  n={len(v)} mean={st.mean(v):+.3f}% median={st.median(v):+.3f}% up-share={100*sum(x>0 for x in v)/len(v):.0f}%')
v=[r['pre30'] for r in rec if r['pre30'] is not None]; print(f'pre-30m run-up mean={st.mean(v):+.3f}% median={st.median(v):+.3f}%')
u=[r['up60'] for r in rec if 'up60' in r]; d=[r['dn60'] for r in rec if 'dn60' in r]
print(f'60m max up mean={st.mean(u):.2f}% median={st.median(u):.2f}%  max down mean={st.mean(d):.2f}% median={st.median(d):.2f}%')
# control: same stocks/days, every bar close as pseudo-signal during 09:30-14:30
ctrl=collections.defaultdict(list)
for (c,dd),s in bars.items():
    for k,b in enumerate(s):
        t=b[0]+timedelta(minutes=5); lt=t.astimezone(KST).time()
        if not(lt.hour*60+lt.minute>=570 and lt.hour*60+lt.minute<=870): continue
        for h in [15,30,60]:
            v=at(s,t+timedelta(minutes=h))
            if v and t+timedelta(minutes=h)<=s[-1][0]+timedelta(minutes=5): ctrl[h].append((v/b[4]-1)*100)
for h in [15,30,60]: print(f'control +{h}m n={len(ctrl[h])} mean={st.mean(ctrl[h]):+.3f}%')
# conditional on pre-30m run-up
print('\nforward +30m by pre-30m run-up')
for lo,hi in [(-99,0.5),(0.5,1.5),(1.5,3),(3,99)]:
    v=[r['h'][30] for r in rec if r['pre30'] is not None and lo<=r['pre30']<hi and 30 in r['h']]
    if v: print(f'  pre30 [{lo},{hi}) n={len(v)} fwd30 mean={st.mean(v):+.3f}% median={st.median(v):+.3f}%')
# control: after a >=1.5% 30m run-up at random bar, what next 30m?
print('\ncontrol (all bars) forward +30m by prior 30m move')
buck=collections.defaultdict(list)
for (c,dd),s in bars.items():
    for k in range(6,len(s)):
        t=s[k][0]+timedelta(minutes=5); lt=t.astimezone(KST).time()
        if not(570<=lt.hour*60+lt.minute<=870): continue
        prior=(s[k][4]/s[k-6][4]-1)*100
        v=at(s,t+timedelta(minutes=30))
        if v is None or t+timedelta(minutes=30)>s[-1][0]+timedelta(minutes=5): continue
        f=(v/s[k][4]-1)*100
        key='<-1.5' if prior<-1.5 else '<-0.5' if prior<-0.5 else '<0.5' if prior<0.5 else '<1.5' if prior<1.5 else '>=1.5'
        buck[key].append(f)
for k in ['<-1.5','<-0.5','<0.5','<1.5','>=1.5']:
    v=buck[k]; print(f'  prior30 {k:>6} n={len(v):4d} fwd30 mean={st.mean(v):+.3f}% median={st.median(v):+.3f}%')
