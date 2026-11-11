"""Keep pre-feature geometry, FULL occupancy and exact decoration changes separate."""
import base64
import re
import struct


def geometric_kind(raw_kind: int, state: str) -> int:
    if raw_kind not in (0, 1, 2):
        raise ValueError('Unknown raw occupancy kind')
    if state.startswith('Block{minecraft:sculk_vein}[') and 'waterlogged=false' in state:
        if raw_kind == 2:
            raise ValueError('Dry sculk vein cannot encode a fluid cell')
        return 0
    return raw_kind


def dry_sculk_feature_pair(a: str, b: str) -> bool:
    """Annotate, NOT erase, changed post-feature occupancy. Full sculk is terrain."""
    empty = {'minecraft:air', 'minecraft:cave_air', 'minecraft:void_air'}
    waterloggable = {'minecraft:sculk_vein', 'minecraft:sculk_sensor', 'minecraft:sculk_shrieker'}
    # The full-block catalyst is a placed feature too; count its change, never call it air.
    features = waterloggable | {'minecraft:sculk_catalyst'}
    ids = []
    for state in (a, b):
        match = re.fullmatch(r'Block\{([^}]+)\}(?:\[[^\]]*\])?', state)
        if not match or 'waterlogged=true' in state:
            return False
        name = match.group(1)
        if name in waterloggable and 'waterlogged=false' not in state:
            return False
        if name not in empty | features:
            return False
        ids.append(name)
    return any(name in features for name in ids)


def decode_deep(report: dict):
    raw = base64.b64decode(report['deep_occupancy_base64'], validate=True)
    packed = base64.b64decode(report['deep_state_indices_u16be'], validate=True)
    if len(packed) != 2 * len(raw):
        raise ValueError('Deep states and occupancy lengths differ')
    palette = report['deep_state_palette']
    ids = [v[0] for v in struct.iter_unpack('>H', packed)]
    if any(i >= len(palette) for i in ids):
        raise ValueError('Invalid deep palette index')
    states = [palette[i] for i in ids]
    normalized = bytes(geometric_kind(k, s) for k, s in zip(raw, states))
    side, stride = report['side'], report['deep_column_stride']
    if side <= 0 or stride <= 0 or side % stride:
        raise ValueError('Invalid sample lattice')
    n = side // stride
    tops = report['deep_column_top_y']
    if len(tops) != n*n:
        raise ValueError('Wrong number of sampled deep columns')
    positions = [(report['x']+(i//n)*stride, y, report['z']+(i%n)*stride)
                 for i, top in enumerate(tops) for y in range(report['deep_min_y'], top+1)]
    if len(positions) != len(raw):
        raise ValueError('Coordinate and deep state lengths differ')
    return raw, states, normalized, positions


def compare_deep(before: dict, after: dict) -> dict:
    a, sa, ga, positions = decode_deep(before)
    b, sb, gb, other_positions = decode_deep(after)
    if positions != other_positions:
        raise ValueError('Deep samples refer to different coordinates')
    changes = [i for i, (s, t) in enumerate(zip(sa, sb)) if s != t]
    geometry_changes = [i for i, (x, y) in enumerate(zip(ga, gb)) if x != y]
    explained = [i for i in geometry_changes
                 if a[i] != 2 and b[i] != 2 and dry_sculk_feature_pair(sa[i], sb[i])]
    explained_set = set(explained)
    unexplained = [i for i in geometry_changes if i not in explained_set]
    def examples(indices):
        return [{'x': positions[i][0], 'y': positions[i][1], 'z': positions[i][2],
                 'before': sa[i], 'after': sb[i], 'raw_before': a[i], 'raw_after': b[i],
                 'geometry_before': ga[i], 'geometry_after': gb[i]} for i in indices[:32]]
    return {'deep_occupancy_samples': len(a),
            'deep_terrain_occupancy_changes': len(geometry_changes),
            'deep_feature_only_occupancy_changes': len(explained),
            'deep_unexplained_occupancy_changes': len(unexplained),
            'raw_deep_occupancy_changes': sum(x != y for x, y in zip(a, b)),
            'deep_block_state_changes': len(changes),
            'deep_open_space_before': ga.count(0), 'deep_open_space_after': gb.count(0),
            'first_deep_state_changes': examples(changes),
            'feature_occupancy_change_examples': examples(explained),
            'unexplained_occupancy_change_examples': examples(unexplained)}


def compare_terrain_stage(before: list, after: list) -> dict:
    """Exact actual TERRAIN block-state comparison. No decorative exclusions here."""
    if len(before) != len(after):
        raise ValueError('Different terrain snapshot window counts')
    samples = chunks = unavailable = 0
    for a, b in zip(before, after):
        for key in ('label', 'x', 'z', 'side'):
            if a[key] != b[key]:
                raise ValueError('Different terrain snapshot coordinates')
        if len(a['chunks']) != len(b['chunks']):
            raise ValueError('Different terrain chunk counts')
        for x, y in zip(a['chunks'], b['chunks']):
            for key in ('chunk_x', 'chunk_z', 'observed_status', 'before_features'):
                if x[key] != y[key]:
                    raise ValueError('Different actual terrain-stage coverage')
            if not x['before_features']:
                unavailable += 1
                continue
            for key in ('samples', 'air_samples', 'fluid_samples', 'exact_block_state_sha256'):
                if x[key] != y[key]:
                    raise ValueError(f'Deep TERRAIN blocks changed: {a["label"]} {x["chunk_x"]},{x["chunk_z"]} {key}')
            if x['samples'] < 0:
                raise ValueError('Negative terrain sample count')
            samples += x['samples']
            chunks += 1
    return {'stage': 'TERRAIN before features', 'windows': len(before), 'chunks_compared': chunks,
            'unavailable_already_decorated_chunks': unavailable, 'deep_block_samples': samples,
            'exact_block_states_identical': True,
            'classification': 'Exact states and coordinates; no block exclusions or normalization'}
