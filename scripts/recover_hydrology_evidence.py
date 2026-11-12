"""Read-only recovery of the exact pre-optimization regression reports."""
import base64
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import time
import urllib.error
import urllib.request

REPO = os.environ['REPO']
ROOT = Path('recovered-evidence')
ROOT.mkdir(exist_ok=True)
EXPECTED = '14254dfab64063990e8a695c2de7e350ba1958cc'
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def get(path, binary=False):
    url = 'https://api.github.com/repos/' + REPO + path
    request = urllib.request.Request(url, headers={
        'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
        'Accept': 'application/vnd.github+json', 'User-Agent': 'FreshGenerator-evidence-audit'})
    try:
        response = urllib.request.build_opener(NoRedirect).open(request, timeout=60)
    except urllib.error.HTTPError as error:
        if error.code not in (301, 302, 303, 307, 308):
            raise
        # Signed artifact/log redirects must not inherit a GitHub bearer token.
        response = urllib.request.urlopen(error.headers['Location'], timeout=60)
    with response:
        data = response.read(40000000)
    return data if binary else json.loads(data)

blobs = {
    'structures-negative': '8b47e52e7197d5868ce6c2838a701f8703bf9f91',
    'hydrology-reverse': '68cf193a1cbcaf3a33c445a27cb8ac5b65d6a909',
    'hydrology-normal': '42aedf0ae49450bc3886d40b2ab961295010bb55',
}
for name, job in [('villages-42', 131346079001), ('villages-1337', 131346077977), ('villages-negative', 131346078042)]:
    try:
        log = get('/actions/jobs/' + str(job) + '/logs?audit=' + str(time.time_ns()), True).decode('utf-8-sig')
        (ROOT / (name + '.ci.log')).write_text(log)
        matches = re.findall(r'CI_EVIDENCE_STORED (\{[^\n]+\})', log)
        if not matches:
            raise ValueError('No stored evidence marker')
        blobs[name] = json.loads(matches[-1])['blob']
    except Exception as error:
        print('LOG_RECOVERY_FAILURE', name, type(error).__name__, str(error))

for name, sha in blobs.items():
    blob = get('/git/blobs/' + sha)
    raw = base64.b64decode(blob['content'], validate=False)
    assert hashlib.sha1(('blob ' + str(len(raw)) + '\0').encode() + raw).hexdigest() == sha
    bundle = json.loads(gzip.decompress(raw))
    assert bundle['identity']['GITHUB_SHA'] == EXPECTED
    assert bundle['identity']['GITHUB_RUN_ID'] == '43264955560'
    folder = ROOT / name
    folder.mkdir(exist_ok=True)
    (folder / 'bundle-identity.json').write_text(json.dumps(bundle['identity'], indent=2))
    docs = {}
    for path, entry in bundle['files'].items():
        data = base64.b64decode(entry['content'])
        assert hashlib.sha256(data).hexdigest() == entry['sha256']
        relative = Path(path)
        assert not relative.is_absolute() and '..' not in relative.parts
        target = folder / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        if path.endswith('.json'):
            docs[relative.name] = json.loads(data)
    print('RECOVERED', name, sha, 'files', len(bundle['files']))
    for a, b in [('candidate.json', 'reverse.json'), ('candidate.json', 'parallel.json'),
                 ('candidate.json', 'reload.json'), ('normal-baseline.json', 'normal-candidate.json')]:
        if a not in docs or b not in docs or 'windows' not in docs[a]:
            continue
        differences = []
        for wa, wb in zip(docs[a]['windows'], docs[b]['windows']):
            assert wa['site'] == wb['site']
            for i, (ca, cb) in enumerate(zip(wa['columns'], wb['columns'])):
                signature = lambda c: c[1:] if a.startswith('normal') else (c[2], c[3], c[4] & 15) if c[5] else None
                if signature(ca) != signature(cb):
                    differences.append({'site': wa['site'], 'x': wa['site']['x'] + i % 33 - 16,
                        'z': wa['site']['z'] + i // 33 - 16, 'before': ca, 'after': cb})
            if a.startswith('normal') and wa['surfaceBiomes'] != wb['surfaceBiomes']:
                print('BIOME_DIFFERENCE', wa['site'], wa['surfaceBiomes'], wb['surfaceBiomes'])
        print('COLUMN_DIFF', name, a, b, 'count', len(differences), json.dumps(differences[:12]))
        (folder / (a + '-vs-' + b)).write_text(json.dumps(differences, indent=2))
