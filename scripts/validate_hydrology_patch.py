"""Check the exact hydrology scope against the packaged 0.3.6 release."""

import hashlib
import json
from pathlib import Path
from zipfile import ZipFile

from village_checks import require

VERSION = "0.3.8-alpha.1"
PREVIOUS = "0.3.6-alpha.1"
PREFIX = "FreshGenerator-Fabric-26.3-"
JAR = PREFIX + VERSION + ".jar"
REFERENCE = Path("dist") / (PREFIX + PREVIOUS + ".jar")
WORLDGEN = "data/freshgenerator/worldgen/"
TERRAIN = WORLDGEN + "density_function/highlands_v2/"
DENSITY = TERRAIN + "coherent/"
HEIGHT = DENSITY + "height.json"
CAVE_WEIGHT = DENSITY + "cave_weight.json"
PRELIMINARY = TERRAIN + "preliminary_surface_level.json"
SETTINGS = WORLDGEN + "noise_settings/highlands_v2.json"
CLIMATE = {
    TERRAIN + "ecotone/biome_depth.json",
    TERRAIN + "regions/biome_temperature.json",
    TERRAIN + "regions/biome_vegetation.json",
}
MANIFEST = "freshgenerator-worldgen-manifest.json"
SURFACE = DENSITY + "surface_density.json"
CHANGED = {HEIGHT, CAVE_WEIGHT, SETTINGS, PRELIMINARY, SURFACE} | CLIMATE
ADDED = {DENSITY + "hydrology/" + name + ".json"
         for name in ("base_height", "base_depth", "base_cave_weight", "cut", "model", "reference_cave_weight", "reference_roof_depth")}
MIXINS = ["RandomStateHydrologyMixin", "ClimateSamplerHydrologyMixin", "BiomeSourceHydrologyMixin",
          "NoiseChunkHydrologyMixin", "HydrologyCarverMixin", "TreeHydrologyMixin", "StructureHydrologyMixin", "HydrologyFreezeMixin"]
RUNTIME = {"dev/freshgenerator/" + name + ".class" for name in (
    "core/DrainageModel", "core/DrainageModel$Field", "core/DrainageModel$Fields",
    "core/DrainageModel$Tile", "core/DrainageModel$Grid", "core/DrainageModel$Cell",
    "core/DrainageModel$Raster", "core/DrainageModel$Tiles", "core/DrainageModel$Tiles$Entry",
    "core/DrainageModel$TileFactory", "core/DrainageBasin", "core/DrainageBasin$Spill",
    "core/DrainageBasin$Visit", "hydrology/DrainageDensity", "hydrology/DrainageDensity$Sampler",
    "hydrology/DrainageDensity$OriginalHeight", "hydrology/DrainageDensity$OriginalHeight$Corner",
    "hydrology/HydrologyInitializer", "hydrology/HydrologyAccess", "hydrology/HydrologyAquifer",
    "hydrology/HydrologyBiomes", "hydrology/VillageTreeGuard", *("mixin/" + name for name in MIXINS)
)}
NEW_DIRECTORIES = {DENSITY + "hydrology/", "dev/freshgenerator/hydrology/"}
ENTRYPOINT = "dev.freshgenerator.hydrology.HydrologyInitializer"
DEBUG = {"label": "FreshGenerator drainage", "function": "freshgenerator:highlands_v2/coherent/hydrology/model"}


def check(reference, candidate):
    with ZipFile(reference) as old, ZipFile(candidate) as new:
        old_paths = set(old.namelist())
        new_paths = set(new.namelist())
        require(len(new_paths) == len(new.namelist()), "Duplicate ZIP entry")
        require(new.testzip() is None, "Broken candidate JAR")
        require(old_paths <= new_paths, "Removed files: " + str(sorted(old_paths - new_paths)))
        added = new_paths - old_paths
        require(added - NEW_DIRECTORIES == ADDED | RUNTIME, "Unexpected added files: " + str(sorted(added)))
        changed = {name for name in old_paths if old.read(name) != new.read(name)}
        require(changed == CHANGED | {MANIFEST, "fabric.mod.json", "freshgenerator.mixins.json"},
                "Unexpected changed files: " + str(sorted(changed)))

        metadata = json.loads(old.read("fabric.mod.json"))
        metadata["version"] = VERSION
        metadata["entrypoints"]["main"].append(ENTRYPOINT)
        require(metadata == json.loads(new.read("fabric.mod.json")), "Unexpected loader contract changes")
        mixins = json.loads(old.read("freshgenerator.mixins.json"))
        mixins["mixins"] += MIXINS
        require(mixins == json.loads(new.read("freshgenerator.mixins.json")), "Unexpected mixin changes")
        settings = json.loads(old.read(SETTINGS))
        settings["debug_functions"].append(DEBUG)
        for binding in settings["debug_functions"]:
            if binding['label'] == 'FG cave access: surface':
                binding['function'] = 'freshgenerator:highlands_v2/coherent/hydrology/base_height'
            elif binding['label'] == 'FG cave access: roof':
                binding['function'] = 'freshgenerator:highlands_v2/coherent/hydrology/reference_roof_depth'
        expected_preliminary = old.read(PRELIMINARY).replace(
            b'freshgenerator:highlands_v2/coherent/height', b'freshgenerator:highlands_v2/coherent/hydrology/base_height')
        require(new.read(PRELIMINARY) == expected_preliminary, 'Underground aquifer reference changed')
        require(settings == json.loads(new.read(SETTINGS)), "Unexpected noise settings changes")
        for name in CLIMATE:
            expected = old.read(name).replace(b"freshgenerator:highlands_v2/coherent/depth_blocks",
                                              b"freshgenerator:highlands_v2/coherent/hydrology/base_depth")
            require(new.read(name) == expected, "Changed native regional climate: " + name)
        # Only the reviewed excavation cap may change the alpine exterior.
        surface = json.loads(new.read(SURFACE))
        expected_surface = {"type": "minecraft:range_choice",
            "input": "freshgenerator:highlands_v2/coherent/hydrology/cut",
            "min_inclusive": 0.25, "max_exclusive": 19,
            "when_in_range": {"type": "minecraft:min", "left": json.loads(old.read(SURFACE)),
                "right": "freshgenerator:highlands_v2/coherent/detail/original_surface_density"},
            "when_out_of_range": json.loads(old.read(SURFACE))}
        require(surface == expected_surface, "Unexpected excavation surface cap")
        for name in (HEIGHT, CAVE_WEIGHT):
            alias = DENSITY + "hydrology/base_" + Path(name).name
            require(new.read(alias) == old.read(name), "Original density must be retained byte-for-byte: " + name)

        previous = json.loads(old.read(MANIFEST))
        current = json.loads(new.read(MANIFEST))
        old_entries = previous["generated_sha256"]
        entries = current["generated_sha256"]
        require(set(entries) == set(old_entries) | ADDED, "Worldgen manifest inventory changed unexpectedly")
        require(current["generated_entries"] == len(entries), "Wrong generated entry count")
        require(previous["source_sha256"] == current["source_sha256"], "Changed native source assets")
        require(current["selectable_world_presets"] == ["freshgenerator:world"], "Changed selectable presets")
        for name, digest in entries.items():
            require(hashlib.sha256(new.read(name)).hexdigest() == digest, "Wrong fingerprint: " + name)
        return {
            "reference_version": PREVIOUS,
            "unchanged_runtime_classes": sum(name.endswith(".class") for name in old_paths),
            "added_runtime_classes": len(RUNTIME),
            "generated_resources": len(entries),
            "changed_worldgen_resources": sorted(CHANGED),
            "added_worldgen_resources": sorted(ADDED),
        }


if __name__ == "__main__":
    print("FRESHGENERATOR_HYDROLOGY_PACKAGE_PASS", json.dumps(check(REFERENCE, Path("build/libs") / JAR)))
