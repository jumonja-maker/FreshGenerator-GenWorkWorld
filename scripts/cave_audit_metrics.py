"""Geometry and decoration are different observations; preserve both, normalize only verified thin sculk veins."""
import base64
import struct


def geometric_kind(raw_kind: int, state: str) -> int:
    if raw_kind not in (0, 1, 2):
        raise ValueError('Unknown raw occupancy kind')
    # isSolid() reports sculk vein as solid in this game build, but it is a wall coating.
    # Do NOT ignore full sculk blocks, stone, fluids, unknown mods or other decorations.
    if state.startswith('Block{minecraft:sculk_vein}[') and 'waterlogged=false' in state:
        if raw_kind == 2:
            raise ValueError('Dry sculk vein cannot encode a fluid cell')
        return 0
    return raw_kind


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
    examples = [{'x': positions[i][0], 'y': positions[i][1], 'z': positions[i][2],
                 'before': sa[i], 'after': sb[i], 'raw_before': a[i], 'raw_after': b[i],
                 'geometry_before': ga[i], 'geometry_after': gb[i]}
                for i in changes[:32]]
    return {'deep_occupancy_samples': len(a),
            'deep_terrain_occupancy_changes': sum(x != y for x, y in zip(ga, gb)),
            'raw_deep_occupancy_changes': sum(x != y for x, y in zip(a, b)),
            'deep_block_state_changes': len(changes),
            'deep_open_space_before': ga.count(0), 'deep_open_space_after': gb.count(0),
            'first_deep_state_changes': examples}
