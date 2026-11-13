"""Additional native-fluid regression gate, separate from static geometry tests.

The gate rejects newly flooded dry columns, disappearing planned sources/ice,
changed bare-ground heights and continued water-top changes from tick 200 to
1000. Multi-block water-top changes also fail; one-block in-channel adjustments
before tick 200 are reported explicitly, NOT
called a perfect match of the static surface. This finite gate is not a proof of
worldwide hydrologic or visual correctness.
"""
import hashlib
import json
import sys
from pathlib import Path

from village_checks import require

SIDE = 33
MISSING = -999
TICKS = (0, 200, 1000)
# A one-block voxel/flow transition is reported, not mistaken for a static match.
# Larger adjustments reproduce the unintended backwater steps and block release.
MAX_TRANSIENT_STAGE_ADJUSTMENT = 1


def check(plan, snapshots, meta):
    require(type(meta.get('completed_server_ticks')) is int and meta['completed_server_ticks'] == 1000,
            'Incomplete native tick experiment')
    require(meta.get('native_fluid_updates') is True and meta.get('halo_generated_before_snapshot') is True,
            'Fluid updates or complete pre-snapshot halo not established')
    require(type(meta.get('forced_chunks')) is int and meta['forced_chunks'] > 0, 'No ticking chunks')
    sha = meta.get('jar_sha256', '')
    require(len(sha) == 64 and all(c in '0123456789abcdef' for c in sha), 'Missing package fingerprint')
    require(plan['seed'] == meta['seed'] and plan['windows'], 'Wrong or empty fixed-site plan')
    require(set(snapshots) == set(TICKS), 'Missing checkpoint')
    checkpoints = meta['checkpoints']
    require([c['tick'] for c in checkpoints] == list(TICKS), 'Incorrect checkpoint sequence')
    planned = sum(bool(p) for w in plan['windows'] for p in w['profiles'])
    require(planned > 0, 'Vacuous fluid test: no modeled water')
    for c in checkpoints:
        require(c['planned_columns'] == planned and c['sources_or_ice_at_planned_stage'] == planned
                and c['missing'] == [], 'A planned source/ice block is missing')
    for tick, data in snapshots.items():
        require(data['seed'] == plan['seed'] and data['jar_sha256'] == sha, 'Mismatched seed or JAR')
        require(data['mode'] == 'candidate' and data['settings'] == 'freshgenerator:highlands_v2', 'Wrong generator')
        require(data['minecraft'] == '26.3' and data['fabric_loader'] == '0.20.1'
                and data['java'].split('.')[0].split('+')[0] == '25', 'Wrong runtime')
        require(data['freshgenerator_loaded'] is True and data['full_chunks'] >= 9, 'No actual FULL-world observation')
        require(len(data['windows']) == len(plan['windows']), 'Missing sample window')
        for actual, window in zip(data['windows'], plan['windows']):
            require(actual['site'] == window['site'], 'Moved sample coordinates')
            columns = actual['columns']
            require(len(columns) == SIDE * SIDE and len(actual['modelGround']) == len(columns), 'Incomplete window')
            require(len(window['profiles']) == len(columns) and [c[5] for c in columns] == window['profiles'], 'Changed profile evidence')
            require(all(len(c) == 6 and all(type(v) is int for v in c) for c in columns), 'Non-integral observation')
    metrics, errors = [], []
    for i, window in enumerate(plan['windows']):
        old = snapshots[0]['windows'][i]
        for tick in TICKS[1:]:
            current = snapshots[tick]['windows'][i]
            newly_wet, dry, changes, unstable = [], [], [], []
            for j, (a, b) in enumerate(zip(old['columns'], current['columns'])):
                pos = {'x': window['site']['x'] + j % SIDE - 16, 'z': window['site']['z'] + j // SIDE - 16}
                if a[2] == MISSING and b[2] != MISSING: newly_wet.append({**pos, 'new_top': b[2]})
                if a[5] and b[2] == MISSING: dry.append(pos)
                if a[2] != MISSING and b[2] != MISSING and a[2] != b[2]:
                    changes.append({**pos, 'before': a[2], 'after': b[2], 'difference': b[2] - a[2]})
                if tick == 1000 and b[2] != snapshots[200]['windows'][i]['columns'][j][2]: unstable.append(pos)
            large_stage_changes = [c for c in changes if abs(c['difference']) > MAX_TRANSIENT_STAGE_ADJUSTMENT]
            ground_changes = sum(a != b for a, b in zip(old['modelGround'], current['modelGround']))
            flags = sum(bool(c[5] and c[4] & (4 | 8)) for c in current['columns'])
            row = {'site': window['site'], 'tick': tick, 'new_wet_columns': newly_wet,
                   'dried_modeled_columns': dry, 'changed_existing_water_tops': changes,
                   'large_water_stage_changes': large_stage_changes,
                   'ground_height_changes': ground_changes, 'unsupported_or_lava_columns': flags,
                   'changes_since_tick_200': unstable}
            metrics.append(row)
            if newly_wet: errors.append(f"{window['site']} tick {tick}: newly flooded dry columns")
            if dry or ground_changes or flags: errors.append(f"{window['site']} tick {tick}: water/terrain safety regression")
            if large_stage_changes: errors.append(f"{window['site']} tick {tick}: multi-block water-stage adjustment")
            if unstable: errors.append(f"{window['site']} tick {tick}: water top still changing after 200 ticks")
    return {'seed': plan['seed'], 'jar_sha256': sha, 'planned_columns': planned,
            'completed_server_ticks': 1000, 'new_dry_land_flooding_pass': not errors,
            'errors': errors, 'windows': metrics,
            'scope': 'Finite native tick regression only; all in-channel changes retained, visual/global acceptance separate'}


def main(folder):
    folder = Path(folder)
    plan_name = 'settling-plan.json' if (folder / 'settling-plan.json').exists() else 'plan.json'
    plan = json.loads((folder / plan_name).read_text())
    meta = json.loads((folder / 'settling-meta.json').read_text())
    snapshots = {t: json.loads((folder / f'settling-{t}.json').read_text()) for t in TICKS}
    log = (folder / 'settling.log').read_text()
    require(f"FRESHGENERATOR_SETTLING_COMPLETE {plan['seed']} 1000" in log
            and 'FRESHGENERATOR_SETTLING_FAIL' not in log, 'Native observer did not complete')
    require(not any(word in log for word in ('Server Watchdog', 'OutOfMemoryError', 'A single server tick took')),
            'Native runtime failed')
    report = check(plan, snapshots, meta)
    report['evidence_sha256'] = {name: hashlib.sha256((folder/name).read_bytes()).hexdigest()
                               for name in [plan_name, 'settling-meta.json', 'settling.log',
                                            *[f'settling-{t}.json' for t in TICKS]]}
    (folder / 'settling-validation.json').write_text(json.dumps(report, indent=2) + '\n')
    require(report['new_dry_land_flooding_pass'], '; '.join(report['errors']))
    print('FRESHGENERATOR_SETTLING_PASS', plan['seed'], report['planned_columns'])
    return report


if __name__ == '__main__':
    main(sys.argv[1])
