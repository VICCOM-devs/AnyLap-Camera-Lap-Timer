import json,sys
from PIL import Image, ImageDraw
import numpy as np
from scipy import ndimage
for seed in sys.argv[1:]:
    R=json.load(open(f'/home/claude/blend/out/race10_{seed}.json'))
    worst=[]
    for e in R['events']:
        c=e.get('card')
        if not c: continue
        g=np.array(Image.open(f"/home/claude/blend/out/r10_{seed}/g{e['i']}.pgm"))>0
        m=Image.new('L',(c['w'],c['h']),0); d=ImageDraw.Draw(m)
        for car in c['cars']:
            p=car['poly']; d.polygon([((p[2*k]-c['ox'])/c['K'],(p[2*k+1]-c['oy'])/c['K']) for k in range(4)],fill=255)
        cov=np.array(m)>0
        lab,n=ndimage.label(g&~cov)
        big=max([ (lab==i).sum() for i in range(1,n+1)] or [0])
        worst.append((big,e['i'],int(g.sum()),int((g&cov).sum())))
    worst.sort(reverse=True)
    print(seed,'largest uncovered moving patches (px):',worst[:8])
