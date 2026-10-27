import json,sys
from shapely.geometry import Polygon, box
bad=0;n=0
for seed in sys.argv[1:]:
    R=json.load(open(f'/home/claude/blend/out/race10_{seed}.json'))
    for e in R['events']:
        c=e.get('card')
        if not c: continue
        polys=[Polygon([(car['poly'][2*k],car['poly'][2*k+1]) for k in range(4)]) for car in c['cars']]
        tags=[box(car['tag'][0],car['tag'][1],car['tag'][0]+car['tag'][2],car['tag'][1]+car['tag'][3]) for car in c['cars']]
        for i,t in enumerate(tags):
            n+=1
            if any(t.intersects(p.buffer(2)) for p in polys): bad+=1; print(seed,e['i'],'tag over car',c['cars'][i]['id'])
            if any(t.intersects(o) for j,o in enumerate(tags) if j!=i): bad+=1; print(seed,e['i'],'tag over tag')
            if t.bounds[0]<0 or t.bounds[1]<0 or t.bounds[2]>c['S'] or t.bounds[3]>c['S']: bad+=1; print(seed,e['i'],'off canvas')
print('tags',n,'problems',bad)
