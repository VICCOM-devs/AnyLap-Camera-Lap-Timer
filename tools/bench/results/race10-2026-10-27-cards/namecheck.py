import json,sys
bad=0;n=0
for seed in sys.argv[1:]:
    R=json.load(open(f'/home/claude/blend/out/race10_{seed}.json'))
    for e in R['events']:
        c=e.get('card')
        if not c: continue
        near={x['car'] for x in R['truth'] if abs(x['t']-e['t'])<0.6}
        for car in c['cars']:
            n+=1
            if car['id'] and car['id'] not in near: bad+=1; print(seed,e['i'],e['t'],'outline named',car['id'],'not near; truth near:',near)
print('outlines',n,'names not near the line in truth',bad)
