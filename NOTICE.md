# Sources and boundaries

Original FreshGenerator Java source is MIT licensed. Minecraft, Fabric and their dependencies retain their own licenses. The build reads native world-generation JSON from the resolved Minecraft 26.3 artifact and composes namespaced variants. Minecraft game classes, decompiled game source and other generator mods are not redistributed here. Generated resource provenance is recorded in freshgenerator-worldgen-manifest.json.

No Chunky/TerraForged/Voxy code is included. Using Minecraft's own chunk pipeline is intentional: it keeps vanilla biome/structure/feature rules rather than reimplementing them with unrelated probabilities.
