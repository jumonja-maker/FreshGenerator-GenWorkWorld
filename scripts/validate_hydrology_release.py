"""Release gate for the native, packaged and reproducible hydrology build."""

import gzip
import json
import os
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

import hydrology_checks
from technical_update_checks import compare_folder
from validate_hydrology_patch import JAR, REFERENCE, VERSION, check
from validate_mountain_release import METRICS, checksum, read, save
from village_checks import require
from hydrology_village_checks import compare as compare_villages

SOURCE_JAR = JAR.replace('.jar', '-sources.jar')
SEEDS = (42, 1337, -20261111, 2037157180504249802, 801086821803359396)
BUILD_REPORT = Path('reports/village-release')
CORE_REPORT = 'hydrology-core-observations.json'


def build():
    source = os.environ['GITHUB_SHA']
    jar, sources = (Path('build/libs') / name for name in (JAR, SOURCE_JAR))
    package = check(REFERENCE, jar)
    suites = [ET.parse(p).getroot() for p in Path('build/test-results/test').glob('TEST-*.xml')]
    require(suites and all(int(s.get(k, 0)) == 0 for s in suites for k in ('failures', 'errors', 'skipped')),
            'Missing, failed or skipped Java tests')
    count = sum(int(s.get('tests', 0)) for s in suites)
    require(count >= 250, 'Insufficient Java coverage, including the four aquifer cache regressions')
    metrics = [read(Path('build/reports') / name) for name in METRICS]
    require(all(m[k] == 0 for m in metrics for k in ('failures', 'errors', 'skipped')),
            'Failed or skipped Python checks')
    python_tests = sum(m['tests'] for m in metrics)
    require(python_tests >= 128, 'Insufficient Python coverage')
    # Run on both branch and main publication paths. These synthetic tests add
    # evidence; they do not replace any real-world, village, or structure gate.
    subprocess.run([sys.executable, 'scripts/test_hydrology_core.py'], check=True)
    subprocess.run([sys.executable, 'scripts/test_hydrology_acceptance.py'], check=True)
    core_report = Path('build') / BUILD_REPORT / CORE_REPORT
    file = Path('build') / BUILD_REPORT / 'reproducible.sha256'
    hashes = {line.split()[1]: line.split()[0] for line in file.read_text().splitlines()}
    require(all(hashes[str(f)] == checksum(f) for f in (jar, sources)), 'Nonreproducible game or source JAR')
    data = {'source_commit': source, 'version': VERSION, 'java_tests': count, 'python_tests': python_tests,
            'jar_sha256': checksum(jar), 'sources_sha256': checksum(sources), 'reference_sha256': checksum(REFERENCE),
            'core_geometry_report_sha256': checksum(core_report),
            'reproducible_build': True, **package}
    save(Path('build') / BUILD_REPORT / 'build.json', data)
    print('FRESHGENERATOR_HYDROLOGY_BUILD_PASS', json.dumps(data))


def prior_regressions(root, source):
    villages = {}
    technical = Path('build/reports/technical')
    technical.mkdir(parents=True, exist_ok=True)
    for seed in SEEDS[:3]:
        villages[str(seed)] = compare_villages(root / f'technical-world-fresh-{seed}', source, seed)
        folder = root / f'technical-structures-{seed}'
        metadata = read(folder / f'meta-{seed}.json')
        require(metadata['source_commit'] == source and metadata['seed'] == seed
                and metadata['actual_server_runs'] == 2, 'Stale structure evidence')
        for name, digest in metadata['files_sha256'].items():
            require(Path(name).name == name and checksum(folder / name) == digest, 'Wrong structure evidence hash')
            shutil.copyfile(folder / name, technical / name)
        relief = read(technical / f'fg-{seed}-envelope.json')['alpine_detail']
        require(relief['changed_voxels'] >= 50 and relief['sign_reversals'] == 0
                and relief['max_absolute_density_correction'] <= 0.1201
                and relief['deep_zero_checks'] > 0 and relief['high_zero_checks'] > 0, 'Alpine detail regressed')
    require(sum(v['villages'] for v in villages.values()) >= 9, 'Insufficient village coverage')
    normal = compare_folder(root / 'technical-world-normal-42', source, 'normal', 42)
    subprocess.run([sys.executable, 'scripts/summarize_technical_audit.py'], check=True)
    topology = read(technical / 'technical-audit.json')
    require(topology['source_commit'] == source and topology['server_runs'] == 6, 'Missing native structure runs')
    for seed in SEEDS[:3]:
        report = topology['summary'][f'fg-{seed}']
        require(report['detached_sky_terrain_components'] == 0 and report['village_suspended_piece_candidates'] == 0,
                'Floating terrain or village')
        require(report['native_structures_equal'] == 52 and report['native_structure_sets_equal'] == 21,
                'Changed native structure rules')
    return {'villages': villages, 'normal': normal, 'structure_summary': topology['summary']}


def publish():
    source = os.environ['GITHUB_SHA']
    root = Path('ci-evidence')
    compiled = root / 'technical-build'
    build_data = read(compiled / BUILD_REPORT / 'build.json')
    require(build_data['source_commit'] == source and build_data['version'] == VERSION, 'Stale build data')
    require(checksum(compiled / BUILD_REPORT / CORE_REPORT) == build_data['core_geometry_report_sha256'],
            'Missing or mismatched core geometry evidence')
    jar, sources = (compiled / 'libs' / name for name in (JAR, SOURCE_JAR))
    require(checksum(jar) == build_data['jar_sha256'] and checksum(sources) == build_data['sources_sha256'],
            'Mismatched release hashes')
    check(REFERENCE, jar)
    hydrology = {}
    for seed in SEEDS:
        folder = root / f'hydrology-{seed}'
        result = hydrology_checks.compare(folder, source, seed, require_dh=seed == 42)
        modes = hydrology_checks.MODES + (('distant-horizons',) if seed == 42 else ())
        for mode in modes:
            expected = build_data['reference_sha256'] if mode in ('baseline', 'normal-baseline', 'normal-repeat-1', 'normal-repeat-2') else build_data['jar_sha256']
            require(read(folder / f'{mode}.json')['jar_sha256'] == expected, 'Server used a different packaged JAR')
        hydrology[str(seed)] = result
    timings = [r['chunk_generation_ms'] for r in hydrology.values()]
    baseline = sum(row['baseline'] for row in timings)
    candidate = sum(row['candidate'] for row in timings)
    require(baseline > 0 and candidate <= 2 * baseline, 'FULL generation exceeds twice the paired baseline')
    lake_levels = {y for result in hydrology.values() for row in result['windows'] for y in row['matched_lake_levels']}
    require(len(lake_levels) >= 3, 'Insufficient observed lake stages')
    require(any(row['kind'] == 'throughflow-lake' and row['matched_lake_columns'] >= 24
                for result in hydrology.values() for row in result['windows']), 'No observed throughflow lake')
    regressions = prior_regressions(root, source)

    dist = Path('dist')
    audits = dist / 'audits'
    audits.mkdir(parents=True, exist_ok=True)
    for file in (jar, sources):
        path = dist / file.name
        shutil.copyfile(file, path)
        path.with_suffix('.jar.sha256').write_text(f'{checksum(path)}  {path.name}\n')
    data = {**build_data, 'hydrology': hydrology, 'previous_regressions': regressions,
            'paired_generation_ms': {'baseline': baseline, 'candidate': candidate},
            'run_url': f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
            'evidence': read(root / 'index.json')}
    save(dist / f'validation-{VERSION}.json', data)
    raw = {str(p.relative_to(root)): read(p) for p in root.glob('hydrology-*/*.json')}
    (audits / f'hydrology-{VERSION}.json.gz').write_bytes(gzip.compress(
        json.dumps(raw, sort_keys=True, separators=(',', ':')).encode(), mtime=0))
    print('FRESHGENERATOR_HYDROLOGY_RELEASE_PASS', build_data['jar_sha256'])


if __name__ == '__main__':
    {'build': build, 'publish': publish}[sys.argv[1]]()
