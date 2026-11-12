"""Exact-ID and broad-land-family statistics; boundary components excluded from size estimates.
Maps are sampled native biome fields, not complete chunks or estimates of infinite-world rarity.
"""
from collections import Counter, deque

FAMILIES = {
 'plain': {'plains','sunflower_plains','snowy_plains','ice_spikes'},
 'forest': {'forest','birch_forest','old_growth_birch_forest','dark_forest','flower_forest','pale_garden','dappled_forest'},
 'taiga': {'taiga','snowy_taiga','old_growth_pine_taiga','old_growth_spruce_taiga'},
 'jungle': {'jungle','sparse_jungle','bamboo_jungle'},
 'desert': {'desert'},'savanna': {'savanna','savanna_plateau','windswept_savanna'},
 'badlands': {'badlands','wooded_badlands','eroded_badlands'}}

def family(name):
    if not name.startswith('minecraft:'): return None
    value=name.split(':',1)[1]
    return next((f for f,biomes in FAMILIES.items() if value in biomes),None)

def decode(m,field='cells'):
    cells=m[field];side=m['side'];palette=m['palette']
    if side<1 or len(cells)!=side*side or m['step']<=0: raise ValueError('Invalid grid dimensions')
    if any(type(i)!=int or not 0<=i<len(palette) for i in cells): raise ValueError('Invalid palette index')
    return [palette[i] for i in cells]

def stats(cells,side,step):
    if len(cells)!=side*side: raise ValueError('Invalid grid')
    groups=[family(c) for c in cells];edges=changed=eligible=common_changed=family_changed=0
    for x in range(side):
        for z in range(side):
            i=x*side+z
            for j in ([i+side] if x+1<side else [])+([i+1] if z+1<side else []):
                edges+=1;changed+=cells[i]!=cells[j]
                if groups[i] is not None and groups[j] is not None:
                    eligible+=1;common_changed+=cells[i]!=cells[j];family_changed+=groups[i]!=groups[j]
    seen=bytearray(len(cells));sizes=[];common_sizes=[];per_id={};small=0;boundary=0
    for start in range(len(cells)):
        if seen[start]: continue
        seen[start]=1;q=deque([start]);n=0;touches=False;name=cells[start]
        while q:
            i=q.popleft();x,z=divmod(i,side);n+=1;touches|=x==0 or z==0 or x==side-1 or z==side-1
            for j in ([i-side] if x else [])+([i+side] if x+1<side else [])+([i-1] if z else [])+([i+1] if z+1<side else []):
                if not seen[j] and cells[j]==name: seen[j]=1;q.append(j)
        if touches:boundary+=1;continue
        sizes.append(n);per_id.setdefault(name,[]).append(n)
        if family(name) is not None:
            common_sizes.append(n)
            if n<=4:small+=1
    def quantile(values,f):
        if not values:return None
        v=sorted(values);return v[round((len(v)-1)*f)]*step*step
    return {'sample_points':len(cells),'step_blocks':step,'biome_cells':dict(sorted(Counter(cells).items())),
      'all_neighbor_edges':edges,'exact_id_crossings':changed,'common_neighbor_edges':eligible,
      'common_id_crossings':common_changed,'common_family_crossings':family_changed,
      'common_id_boundary_fraction':common_changed/eligible if eligible else 0,
      'common_family_boundary_fraction':family_changed/eligible if eligible else 0,
      'interior_components':len(sizes),'boundary_components_not_used_for_area':boundary,
      'common_interior_components':len(common_sizes),'common_small_components_le4_cells':small,
      'common_component_area_median_blocks2':quantile(common_sizes,.5),'common_component_area_p90_blocks2':quantile(common_sizes,.9),
      'per_biome_interior_component_counts':{k:len(v) for k,v in sorted(per_id.items())},
      'per_biome_median_component_area_blocks2':{k:quantile(v,.5) for k,v in sorted(per_id.items())}}
