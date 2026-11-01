#!/usr/bin/env python3
"""Merge photo-finish fragments: ticks < 2.5 frames and < 0.15 along apart are one car."""
import json,sys
src,fps,out=sys.argv[1],float(sys.argv[2]),sys.argv[3]
S=sorted(json.load(open(src)),key=lambda s:s['t'])
dt=2.5/fps;keep=[]
for s in S:
    near=[k for k in keep if abs(k['t']-s['t'])<dt and abs(k['along']-s['along'])<0.15]
    if near:
        k=max(near,key=lambda k:k['px'])
        if s['px']>k['px']: s2=dict(s);s2['px']+=k['px'];keep[keep.index(k)]=s2
        else: k['px']+=s['px']
    else: keep.append(dict(s))
json.dump(keep,open(out,'w'));print(len(S),'->',len(keep))
