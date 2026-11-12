"""Check the candidate game JAR against the published alpine-detail build."""

import hashlib
import json
from pathlib import Path
from zipfile import ZipFile

from village_checks import require


JAR = "FreshGenerator-Fabric-26.3-0.3.6-alpha.1.jar"
WORLDGEN = "data/freshgenerator/worldgen/"
DENSITY = WORLDGEN + "density_function/highlands_v2/coherent/"
HEIGHT = DENSITY + "height.json"
CAVE_WEIGHT = DENSITY + "cave_weight.json"
MANIFEST = "freshgenerator-worldgen-manifest.json"
ADDED = {
    DENSITY + "hydrology/" + name + ".json"
    for name in ("base_height", "base_cave_weight", "cut")
}
RUNTIME = {
    "dev/freshgenerator/" + name + ".class"
    for name in ("core/DrainageModel", "core/DrainageModel$Field", "core/DrainageModel$Tile",
                 "core/DrainageModel$Grid", "hydrology/DrainageDensity",
                 "hydrology/DrainageDensity$Sampler", "hydrology/HydrologyInitializer")
}
NEW_DIRECTORIES = {DENSITY + "hydrology/", "dev/freshgenerator/hydrology/"}
ENTRYPOINT = "dev.freshgenerator.hydrology.HydrologyInitializer"


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
        require(changed == {HEIGHT, CAVE_WEIGHT, MANIFEST, "fabric.mod.json"},
                "Unexpected changed files: " + str(sorted(changed)))
        metadata = json.loads(old.read("fabric.mod.json"))
        metadata["entrypoints"]["main"].append(ENTRYPOINT)
        require(metadata == json.loads(new.read("fabric.mod.json")), "Unexpected loader contract changes")
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
        for name, digest in entries.items():
            require(hashlib.sha256(new.read(name)).hexdigest() == digest, "Wrong fingerprint: " + name)
        print(f"Hydrology JAR verified: {len(entries)} resources, two changes, {len(ADDED)} additions")


if __name__ == "__main__":
    check(Path("dist") / JAR, Path("build/libs") / JAR)
