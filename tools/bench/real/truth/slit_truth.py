#!/usr/bin/env python3
"""Photo-finish truth: the pixels ON the line (3x3 mean, every 2 px) over time → (along × time) image; cars are short ticks.
Rolling 90 s median background, ticks = connected components. Independent of the band tracker (make_truth.py) and catches
cars the tracker merges (several cars at the line within ~0.3 s). Usage: slit_truth.py video fps x1 y1 x2 y2 until_s out_prefix
then fragments.py out_prefix.json fps merged.json (a car often leaves a second, small tick ~0.1 along / 1 frame earlier).
If the line lies on a chequered strip, sample a few px beside it (a white car on a white square vanishes).
"""
import subprocess,numpy as np,json,sys
from scipy import ndimage as ndi
vid,fps,x1,y1,x2,y2,t1,out=sys.argv[1],float(sys.argv[2]),*map(float,sys.argv[3:7]),float(sys.argv[7]),sys.argv[8]
N=int(np.hypot(x2-x1,y2-y1)/2)
X0=int(min(x1,x2))-4;Y0=int(min(y1,y2))-4;W=int(abs(x2-x1))+8;H=int(abs(y2-y1))+8;X0-=X0%2;Y0-=Y0%2;W+=W%2;H+=H%2
p=subprocess.Popen(['ffmpeg','-v','error','-t',str(t1),'-i',vid,'-vf',f'crop={W}:{H}:{X0}:{Y0}','-f','rawvideo','-pix_fmt','rgb24','-'],stdout=subprocess.PIPE)
pts=[(x1+(x2-x1)*i/(N-1)-X0,y1+(y2-y1)*i/(N-1)-Y0) for i in range(N)]
xi=np.array([int(round(x)) for x,y in pts]);yi=np.array([int(round(y)) for x,y in pts])
cols=[]
while True:
    b=p.stdout.read(W*H*3)
    if len(b)<W*H*3:break
    f=np.frombuffer(b,np.uint8).reshape(H,W,3).astype(np.float32)
    acc=np.zeros((N,3),np.float32)
    for dx in (-1,0,1):
        for dy in (-1,0,1): acc+=f[np.clip(yi+dy,0,H-1),np.clip(xi+dx,0,W-1)]
    cols.append(acc/9)
a=np.array(cols).transpose(1,0,2)   # N x T x 3
np.save(out+'.npy',a.astype(np.uint8))
# rolling median background (60 s windows) so lighting drift and parked things don't stick
T=a.shape[1];bg=np.zeros_like(a)
step=int(fps*30)
for s in range(0,T,step):
    lo=max(0,s-step);hi=min(T,s+2*step);bg[:,s:s+step]=np.median(a[:,lo:hi:3],axis=1,keepdims=True)
d=np.abs(a-bg).sum(2)
m=d>60
m=ndi.binary_opening(m,structure=np.ones((3,1)))      # at least 3 line samples wide
lab,n=ndi.label(m,structure=np.ones((3,3)))
res=[]
for i,sl in enumerate(ndi.find_objects(lab),1):
    mm=lab[sl]==i;sz=mm.sum()
    if sz<int(N*0.04): continue
    ys,ts=np.nonzero(mm);ys+=sl[0].start;ts+=sl[1].start
    rgb=a[ys,ts].mean(0)
    res.append(dict(t=float(ts.mean()/fps),t0=float(ts.min()/fps),t1=float(ts.max()/fps),along=float(ys.mean()/(N-1)),a0=float(ys.min()/(N-1)),a1=float(ys.max()/(N-1)),px=int(sz),rgb=[float(v) for v in rgb]))
res.sort(key=lambda r:r['t'])
json.dump(res,open(out+'.json','w'));print(len(res),'slit components')
