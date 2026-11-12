# Village metadata bounds: confirmed failure and correction

The failed candidate `72db828c1a3c7f5597fc0ae34f8c3853cebc3242` reached a native `Requested chunk unavailable during world generation` exception from `VillageTerrainPlan.create`, called by `VillageLakeGuard`. The server then failed dependent generation tasks and eventually timed out. Original evidence is retained in `research/rejected-candidates/village-timeout-72db828.json.gz`.

The plan searched eight chunks around the chunk overlapped by a lake. A lake can overlap an adjacent chunk, extending this search to ring nine relative to `WorldGenRegion`. The actual region's prepared dependency radius belongs to its own center. In the target game, `getChunk(..., STRUCTURE_STARTS, false)` does not turn an out-of-bounds request into an optional read.

The correction anchors metadata discovery to `region.getCenter()` while retaining the plan's original target coordinates. The density model, native village layout, full apron, roof depth and all release gates remain unchanged. The bounded village horizon is 96 blocks from the starting chunk origin. With the 24-block apron and native lake bounds `center + [-8,22]`, no start outside the center's eight-chunk halo can influence any lake voxel. Tests cover every horizontal placement offset, negative coordinates, the old ninth-ring counterexample and zero model influence outside the horizon.

This document records an implemented correction, not a successful release. Actual server generation and the strict paired village assertions must still pass before an installable JAR is published.
