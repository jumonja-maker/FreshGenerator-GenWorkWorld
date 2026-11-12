"""Independent union-find and symmetry checks for the biome-map measurement code.
Run from the repository: python3 research/checks/check_biome_metrics.py
This does not generate Minecraft worlds and must not be counted as server validation.
"""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import itertools
import json
import random
from collections import Counter, defaultdict
from pathlib import Path

PIN = '755ea88cbfdf586121ccd4a13063f09ed70743c5'
BIOMES = ['minecraft:plains', 'minecraft:forest', 'minecraft:birch_forest',
          'minecraft:taiga', 'minecraft:jungle', 'minecraft:desert',
          'minecraft:river', 'minecraft:beach', 'minecraft:mushroom_fields',
          'example:forest']
GROUP = dict(zip(BIOMES, ['plain', 'forest', 'forest', 'taiga', 'jungle',
                         'desert', None, None, None, None]))


def reference(cells: list[str], n: int) -> dict:
    """Undirected graph, union-find, and explicit coordinate pairs (not BFS)."""
    parent = list(range(n*n))
    def root(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a
    pairs = [(x*n+z, (x+1)*n+z) for x in range(n-1) for z in range(n)]
    pairs += [(x*n+z, x*n+z+1) for x in range(n) for z in range(n-1)]
    for a, b in pairs:
        if cells[a] == cells[b]:
            parent[root(a)] = root(b)
    parts = defaultdict(list)
    for i in range(n*n):
        parts[root(i)].append(i)
    interior = [v for v in parts.values() if all(
        0 < i//n < n-1 and 0 < i%n < n-1 for i in v)]
    eligible = [(a,b) for a,b in pairs if GROUP[cells[a]] is not None
                and GROUP[cells[b]] is not None]
    common = [v for v in interior if GROUP[cells[v[0]]] is not None]
    return {
        'sample_points': n*n,
        'biome_cells': dict(Counter(cells)),
        'all_neighbor_edges': len(pairs),
        'exact_id_crossings': sum(cells[a] != cells[b] for a,b in pairs),
        'common_neighbor_edges': len(eligible),
        'common_id_crossings': sum(cells[a] != cells[b] for a,b in eligible),
        'common_family_crossings': sum(GROUP[cells[a]] != GROUP[cells[b]] for a,b in eligible),
        'interior_components': len(interior),
        'boundary_components_not_used_for_area': len(parts)-len(interior),
        'common_interior_components': len(common),
        'common_small_components_le4_cells': sum(len(v)<=4 for v in common),
        'per_biome_interior_component_counts': dict(Counter(cells[v[0]] for v in interior)),
    }


def transformations(cells, n):
    a = cells
    for _ in range(4):
        yield a
        yield [a[x*n+n-1-z] for x in range(n) for z in range(n)]
        a = [a[(n-1-z)*n+x] for x in range(n) for z in range(n)]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    default=Path(__file__).resolve().parents[2]/'scripts/biome_region_metrics.py'
    parser.add_argument('--source',type=Path,default=default)
    parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    body=args.source.read_bytes()
    blob=hashlib.sha1(b'blob '+str(len(body)).encode()+b'\0'+body).hexdigest()
    if blob!=PIN:
        raise ValueError(f'Source changed: {blob}; review this check before updating its pin')
    spec=importlib.util.spec_from_file_location('checked_biome_metrics',args.source)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    rng=random.Random(20261112)
    maps=[(3,[BIOMES[v] for v in bits]) for bits in itertools.product(range(2),repeat=9)]
    for _ in range(256):
        n=rng.randint(1,25)
        maps.append((n,[rng.choice(BIOMES) for _ in range(n*n)]))
    symmetries=0;source_checks=0
    for n,cells in maps:
        actual=module.stats(cells,n,16)
        for k,v in reference(cells,n).items():
            assert actual[k]==v,(k,n,actual[k],v)
            source_checks+=1
        for transformed in transformations(cells,n):
            assert module.stats(transformed,n,16)==actual,('Orientation bias',n)
            symmetries+=1
        larger=module.stats(cells,n,64)
        for key in ['common_component_area_median_blocks2','common_component_area_p90_blocks2']:
            assert larger[key]==(None if actual[key] is None else actual[key]*16)
        assert larger['per_biome_median_component_area_blocks2']=={
            k:v*16 for k,v in actual['per_biome_median_component_area_blocks2'].items()}
        shuffled=BIOMES[:];rng.shuffle(shuffled)
        encoded={'side':n,'step':16,'palette':shuffled,'cells':[shuffled.index(v) for v in cells]}
        assert module.decode(encoded)==cells
    # A narrow local river, beach, rare mushroom patch or unknown mod biome must not
    # inflate the "small common biome" error counter merely because it is small.
    for id in BIOMES[-4:]:
        cells=['minecraft:forest']*49;cells[24]=id
        assert module.stats(cells,7,16)['common_small_components_le4_cells']==0
    result={'source_git_blob':blob,'source_sha256':hashlib.sha256(body).hexdigest(),
      'exhaustive_binary_3x3_maps':512,'random_multibiome_maps':256,
      'independent_scalar_or_map_comparisons':source_checks,'rotation_reflection_checks':symmetries,
      'step_area_and_palette_checks':len(maps),'protected_small_role_cases':4,
      'passed':True,'scope':'Measurement-code cross-check only; not Minecraft generation or an ecological realism claim.'}
    text=json.dumps(result,indent=2)+'\n'
    if args.output:
        args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(text)
    print(text,end='')

if __name__=='__main__': main()
