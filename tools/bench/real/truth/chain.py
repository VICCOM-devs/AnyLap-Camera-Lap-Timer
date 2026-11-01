"""Helpers for labelling: label() from make_truth candidates + group map, split_twins() splits identical cars by lap
continuity (greedy chains), report() prints each car's lap rhythm (odd laps = missing/duplicate/wrong label)."""
import json,sys,numpy as np
# label candidates: cluster->car map, drops, overrides, and twin groups split by lap rhythm
def label(cand, cmap, drop=(), over={}, twins={}, until=1e9, minlap=6.0):
    C=json.load(open(cand)); out=[]
    for i,c in enumerate(C):
        if i in drop or c['t']>until: continue
        car=over.get(i, cmap.get(c['guess']))
        if car is None or car=='drop': continue
        out.append(dict(i=i,t=c['t'],car=car,along=c['along']))
    # split twin groups: greedy chains by pace
    for g,names in twins.items():
        xs=[o for o in out if o['car']==g]; xs.sort(key=lambda o:o['t'])
        chains={n:[] for n in names}
        for o in xs:
            best=None
            for n,ch in chains.items():
                if not ch: cost=0 if all(len(c2)>0 or n2==n for n2,c2 in chains.items()) else 0.5; cost=0.1*len([1 for c2 in chains.values() if c2])
                else:
                    dt=o['t']-ch[-1]['t']
                    if dt<minlap: continue
                    pace=np.median(np.diff([p['t'] for p in ch])) if len(ch)>2 else 9.6
                    k=max(1,round(dt/pace)); cost=abs(dt-k*pace)/pace+0.3*(k-1)
                if best is None or cost<best[0]: best=(cost,n)
            if best is None: o['car']='?'+g; continue
            chains[best[1]].append(o); o['car']=best[1]
    return out
def report(out):
    for car in sorted({o['car'] for o in out}):
        ts=[o['t'] for o in out if o['car']==car];l=np.diff(ts);med=np.median(l) if len(l) else 0
        odd=[(round(ts[i],2),round(l[i],2)) for i in range(len(l)) if l[i]<0.8*med or l[i]>1.25*med]
        print(f'{car:12s} {len(ts):3d}  lap {med:5.2f}  odd {odd}')

def split_twins(out, twins, minlap=6.0):
    for g,names in twins.items():
        xs=sorted([o for o in out if o['car']==g],key=lambda o:o['t']); chains={n:[] for n in names}
        for o in xs:
            best=None
            for n,ch in chains.items():
                if not ch: cost=0.1*sum(1 for c2 in chains.values() if c2)
                else:
                    dt=o['t']-ch[-1]['t']
                    if dt<minlap: continue
                    pace=np.median(np.diff([p['t'] for p in ch])) if len(ch)>2 else 9.6
                    k=max(1,round(dt/pace)); cost=abs(dt-k*pace)/pace+0.3*(k-1)
                if best is None or cost<best[0]: best=(cost,n)
            if best is None: o['car']='?'+g; continue
            chains[best[1]].append(o); o['car']=best[1]
    return out
