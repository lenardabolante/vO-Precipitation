"""Deterministic, per-cloud name generation.

Every cloud's name is derived only from its own RNG (see clouds.py), never from
shared global random state -- that is what makes clouds independently unique
rather than correlated by spawn order.
"""
from __future__ import annotations

TYPE_PREFIX = {
    "cumulus": "Cu",
    "cumulonimbus": "Cb",
    "nimbostratus": "Ns",
    "stratocumulus": "Sc",
    "altostratus": "As",
    "altocumulus": "Ac",
    "stratus": "St",
    "cirrus": "Ci",
    "supercell": "Sp",
}

_ADJECTIVES = [
    "Tall", "Quiet", "Restless", "Pale", "Iron", "Low",
    "Bright", "Distant", "Hollow", "Drifting", "Heavy", "Faint",
    "Dark", "Slow", "Swift", "Broken", "Gentle", "Wild", "Cold",
    "Warm", "Young", "Lone", "Stormy",
    "Ember", "Frost", "Misty",
    "Tattered", "Velvet", "Swollen", "Sullen", "Towering",
    "Ragged", "Rolling", "Shifting", "Hushed",
    "Solemn", "Northern", "Fleeting", "Looming",
    "Sleeping", "Howling", "Radiant", "Weary",
    "Vast", "Rumbling", "Sunken",
]

_NOUNS = [
    "Ember", "Wing", "Drift", "Tide", "Bloom",
    "Shroud", "Veil", "Shadow", "Spire", "Hollow",
    "Plume", "Gate", "Path",
    "Willow", "Anvil", "Curtain", "Lantern", "Current",
    "Horizon", "Crest",
    "Tower", "Cradle", "Thistle", "Sleeve",
    "Gale", "Squall", "Tempest", "Zephyr", "Halo",
    "Cairn", "Sentinel",
    "Lighthouse", "Pillar", "Sail",
]


def make_name(rng, cloud_type: str, cloud_id: int) -> str:
    """Build e.g. 'Cb-0042 "Tall Ember"' using only the cloud's own RNG."""
    prefix = TYPE_PREFIX.get(cloud_type, "Cu")
    adj = rng.choice(_ADJECTIVES)
    noun = rng.choice(_NOUNS)
    return f'{prefix}-{cloud_id:04d} "{adj} {noun}"'


# -- settlements ---------------------------------------------------------------
_S_HEAD = [
    "Elder", "Oak", "Stone", "Mill", "River", "Ash", "Frost", "Amber", "Raven",
    "Fox", "Wren", "Bright", "Grey", "High", "Low", "North", "West", "East",
    "Cold", "Green", "Red", "Salt", "Thorn", "Wolf", "Iron", "Maple", "Birch",
    "Clear", "Marsh", "Hollow", "Sun", "Moon", "Cinder", "Briar", "Lark",
]
_S_TAIL_SMALL = ["moor", "wick", "stead", "by", "thorpe", "ley", "field", "brook",
                 "hurst", "holm", "fold", "cot", "combe", "worth"]
_S_TAIL_BIG = ["ford", "ton", "bridge", "haven", "port", "gate", "mouth", "bury",
               "minster", "chester", "wall", "stoke", "shire", "burgh"]


def make_settlement_name(rng, kind: str, used: set) -> str:
    """Unique name such as 'Eldermoor' (hamlet/village) or 'Ravenford' (town/city)."""
    tails = _S_TAIL_SMALL if kind in ("hamlet", "village") else _S_TAIL_BIG
    for _ in range(60):
        name = rng.choice(_S_HEAD) + rng.choice(tails)
        if name not in used:
            used.add(name)
            return name
    n = 2
    base = rng.choice(_S_HEAD) + rng.choice(tails)
    while f"{base} {n}" in used:
        n += 1
    name = f"{base} {n}"
    used.add(name)
    return name
