"""Summarise actual observations. Findings are never converted into a claim of no bugs."""
import json, os, sys
from pathlib import Path
p=Path('build/reports/technical')
seeds=[int(seed) for seed in sys.argv[1:]] or [42,1337,-20261111]
reports={}
for seed in seeds:
    for mode in ['fg','vanilla']:
        label=f'{mode}-{seed}'
        r=json.loads((p/f'{label}.json').read_text())
        assert r['completed'] is True and r['seed']==seed and r['structure_generation_enabled']
        props=(p/f'{label}.properties').read_text().replace(chr(92)+':', ':')
        expected='freshgenerator:world' if mode=='fg' else 'minecraft:normal'
        assert f'level-type={expected}\n' in props
        assert len(r['regions'])==4 and r['villages_examined']>=1
        assert r['spawn']['dry_supported_two_block_spaces']>0
        assert r['placement_rules']['candidate_positions_compared']>0
        assert len(r['structures'])>=3
        reports[label]=r
for seed in seeds:
    assert reports[f'fg-{seed}']['placement_rules']==reports[f'vanilla-{seed}']['placement_rules'], 'Native rules differ from vanilla control'
summary={}
for label,r in reports.items():
    villages=[s for s in r['structures'] if 'village_' in s['id']]
    summary[label]={
        'full_chunks_explicitly_requested':r['explicit_full_chunks_requested'],
        'terrain_block_positions_scanned':sum(x['scanned_block_positions'] for x in r['regions']),
        'detached_sky_terrain_components':sum(x['sky_exposed_detached_terrain_components'] for x in r['regions']),
        'isolated_single_blocks':sum(x['isolated_single_blocks'] for x in r['regions']),
        'boundary_unknown_components':sum(x['boundary_unknown_components'] for x in r['regions']),
        'villages_examined':len(villages),
        'structures_examined':len(r['structures']),
        'structure_pieces_examined':sum(s['pieces'] for s in r['structures']),
        'village_rigid_pieces_checked':sum(s['village_support']['rigid_pieces_checked'] for s in villages),
        'village_suspended_piece_candidates':sum(s['village_support']['all_columns_over_void_piece_candidates'] for s in villages),
        'village_door_exit_candidates':sum(s['village_support']['doors_without_local_supported_exit'] for s in villages),
        'native_structures_equal':r['placement_rules']['structures_equal_to_native'],
        'native_structure_sets_equal':r['placement_rules']['structure_sets_equal_to_native'],
        'candidate_positions_compared':r['placement_rules']['candidate_positions_compared'],
        'warnings':r['warnings']
    }
result={
    'source_commit':os.environ.get('GITHUB_SHA','local'),
    'run_url':f"{os.environ.get('GITHUB_SERVER_URL','')}/{os.environ.get('GITHUB_REPOSITORY','')}/actions/runs/{os.environ.get('GITHUB_RUN_ID','')}",
    'seeds':seeds,'server_runs':len(reports),
    'status':'completed_with_findings' if any(x['warnings'] for x in reports.values()) else 'sampled_checks_passed',
    'placement_rules_equal_to_vanilla_controls':True,
    'generation_or_block_cleanup_changes':False,
    'claims_not_made':['No floating blocks anywhere in an unlimited world','All structure interiors or villager pathfinding are correct','Vanilla and FG have equal structure frequency per area','Third-party biome/structure/DH compatibility'],
    'method':'FULL halo, six-connected occupied components, natural-material filter, structure/cave/sea exclusions; boundary contact is unknown. Village floor/door checks are conservative geometry observations, not navigation proofs.',
    'summary':summary,'scenarios':reports
}
(p/'technical-audit.json').write_text(json.dumps(result,indent=2)+'\n')
print('FRESHGENERATOR_TECHNICAL_AUDIT_COMPLETE '+json.dumps({'status':result['status'],'summary':summary}))
