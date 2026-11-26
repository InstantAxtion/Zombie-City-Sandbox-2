# Plan: Zombie City Sandbox 2 at real national scale

## 0. What I read and measured

I read Region, RegionGen, RoadGraph, Tile, Flow, NavPath, Spatial, Sectors, World, SaveIO, MapPainter, Vehicles, Fires, Director, Replay, Camera, Noise and Rng. I also grepped every module for how it indexes tiles, sectors, buildings and settlements: Strategic, Forces, Logistics, Society, Ecology, Navy, Infrastructure, Engineers, Roles, Setup, Dispatch, Command, Media, Stats, WorldView and Game.

I ran the probes from the existing compiled classes in `build/test` and wrote no files.

**`Mem` (seed 7, Coast; heap in use after GC):**

| Map | World memory | Build time | Buildings | People |
|---|---|---|---|---|
| 1536 | 33 MB | 0.88 s | 2,173 | 1.25M |
| 2304 | 61 MB | 1.17 s | 3,662 | 2.26M |
| 3072 | 103 MB | 1.90 s | 6,005 | 3.31M |

**Where the memory goes**, worked out from the array sizes in the code (they add up to the measurements):

| Map | `Region.tile` | `bldAt` (char per tile) | `nav` | `Flow` (3 shorts per nav cell) | `NavPath` (12 B per nav cell) | `Spatial` | Sum |
|---|---|---|---|---|---|---|---|
| 2304 | 5.3 | 10.6 | 1.3 | 8.0 | 15.9 | 5.3 | 46 of 61 MB |
| 3072 | 9.4 | 18.9 | 2.4 | 14.2 | 28.3 | 9.4 | 83 of 103 MB |

- At 2304, `RegionGen.height` (a float per tile) adds another 21 MB of garbage while the map is being built.
- If the same dense layout were used for a 28,672-tile map, it would need about 6.7 GB:
  - tiles 822 MB
  - `bldAt` 1.6 GB
  - `NavPath` 2.5 GB
  - `Flow` 617 MB
  - `Spatial` cells 822 MB, plus `lBlk`/`zBlk` 26 MB
  - the `Vehicles` 128 px grid 103 MB
  - `RoadGraph` node buckets 13 MB
- So every per-tile and per-cell array has to become chunked or active-area-only.

**Speed (`Probe`, 2304 map, 20,000 dead):**
- 3.28 ms per street step.
- Strategic fast-forward: 3 game days (432 ten-second steps) in 1.0 s. That is about 2.3 ms per step for 5,184 sectors with every module running, an upper bound of roughly 450 ns per sector per update on this 4-core desktop. Assume a phone is 3-4 times slower.

### Code facts that drive the design
1. `Region` holds dense `tile[W*H]`, `bldAt` (char per tile, 65,534-building limit), and `nav[]` (32 px cells).
   - `Flow` and `NavPath` index `r.nav` linearly.
   - `SaveIO` writes the whole `tile[]` and the whole flow field.
2. `RoadGraph.addEdge` scans every edge to avoid duplicates, so building it costs O(E²). At about 300k edges that is impossible.
   - The node bucket grid is 256 px, which is 3.2M buckets at 28,672 tiles.
   - `Region.closeBarricadedRoads()` with no bounds samples tiles along every edge.
3. About 70 loops run over every sector (`for s < sec.n`), and 26 loops of the form "settlement[s] == st" make the cost O(settlements × sectors). They are in Strategic (evacuation, homecoming, hordes), Society, Logistics, Infrastructure, Media, Roles, Setup, Dispatch, Forces and Stats.
   - The horde target search is O(sectors) for each horde that rethinks.
   - `Sectors.update` recomputes `cam[]` and runs `drift()` (8 neighbours × `attraction()`) for every sector every second.
   - `Spatial.build` does `Arrays.fill` on four per-sector arrays and on `lBlk`/`zBlk` every step. At national size that is about 25 MB cleared per step.
4. `Director` stores 2 × n bytes per game hour for the timelapse. At 200k sectors that is 400 KB per hour, or 288 MB over 30 days.
5. Init-time scans read every 4th tile of every sector (Ecology terrain costs, Navy coast, Infrastructure farms, the Game land mask), and the New Game preview paints every tile of a freshly generated region.
6. Every per-building array is sized by `r.bCount`: Fires (6 arrays), `World.doorX/doorY`, `secBuildings`, `WorldView.scorched` and its bucket index. Society picks a random building from the whole country for looting.
7. Two clocks:
   - Street level: one simulated second is one game minute at 1x, and people walk 20 px/s (about 0.3 km per game hour).
   - National level: the speeds look the same in px/s but were tuned to a 9 km country:

| Mover | Now (px/s) | Now (per game hour) | Crossing today's 9.2 km |
|---|---|---|---|
| Horde (literal 11 in `Strategic:400`) | 11 | 165 m | 56 h |
| Refugees (literal 28 in `Strategic:835`) | 28 | 420 m | – |
| Convoy | 35 | 525 m | 17.5 h |
| Train | 90 | 1.35 km | – |
| Formations | 26-45 | 0.4-0.7 km | about 19 h |

8. Things that already work in our favour:
   - `Noise` and `Rng.hash/unit` are pure functions of (seed, x, y), so any chunk can be generated on its own.
   - The camera is already a recorded input.
   - `Ents` positions are floats; at 590k px the precision is about 1/16 px, which is fine.

## 1. Target numbers

- **Tile size:** stays 16 px = 4 m.
- **Map sizes:**

| Map | Side | Width | Sectors (64 tiles) | Districts | Coarse cells (16 tiles) |
|---|---|---|---|---|---|
| State | 20,480 | 82 km | 102,400 | 1,600 | 1.64M |
| Country | 28,672 | 115 km | 200,704 | 3,136 | 3.2M |
| Large | 36,864 | 147 km | 331,776 | 5,184 | 5.3M |

- **Settlements (Country map):**

| Kind | Count | Population | Size |
|---|---|---|---|
| Capital | 1 | 1.2M | 16-19 km across, about 225 km², about 5,300/km² on average (20k/km² in the core); about 200k buildings, about 6 residents per building |
| Cities | 5 | 150k-450k | 7-13 km across |
| Towns | ~50 | 5k-40k | 1.6-4 km across |
| Villages | ~250 | 300-2,500 | – |
| Countryside | – | 20-50/km² (about 300k in all) | – |

  The Country map holds about 3.5M people and about 550k buildings, none of them stored nationally.
- **Street lattice:** one global lattice. Streets every 16 tiles (64 m), avenues every 64 tiles (256 m), which fall exactly on chunk and sector borders. Arterials every 192 tiles. Superblocks merge lattice cells inside a chunk by dropping minor streets according to a hash, so blocks come out 13, 29, 45 or 61 tiles wide. Blocks never cross a chunk border, so each building belongs to exactly one chunk.
- **Chunk:** 64×64 tiles, the same square as a sector (one to one).
  - Holds tiles (4 KB), a local building index (short[4096], 8 KB), 32×32 nav (1 KB), buildings stored as structure-of-arrays with doors (about 60 on average, up to about 300; 2-10 KB) and the residents worked out for each building.
  - About 16-24 KB resident.
  - A chunk is 4 MapPainter paint chunks (32 tiles each).
- **Sectors:** 64 tiles (256 m), up from 32.
  - Above 50k sectors, the per-sector arrays must be slimmed to 80 B or less in total. Today all modules together use about 110 B: `driftX`/`driftY` become bytes, `hot`/`panic` become shorts.
  - At 80 B: about 16 MB on the Country map, 26 MB on the Large map.
- **Districts:** 8×8 sectors (2 km). Each keeps sums (dead, living, infected), a flag for whether anything is happening, an "awake" list and a name.
- **Coarse national grid:** 16-tile cells (64 m). One byte of land cover plus one byte of height. 6.4 MB on the Country map, 10.6 MB on the Large map.
- **Road graph (Country):** every street stored, about 155k junctions and 300k roads, about 16 MB. The highway and arterial level is about 20k junctions.
- **Memory budget, phone (Country map, 256 MB `largeHeap`):**

| Item | MB |
|---|---|
| Coarse grid | 6.4 |
| Nation data (rivers, roads as lines, lattice settings, facilities, settlements) | 2 |
| Road graph | 16 |
| Sectors (slimmed) | 16-22 |
| Chunk cache, 768 × 20 KB | 15 |
| Active-area sim pages, up to 512 chunks × 22 KB (`Flow` 6 KB, `NavPath` 12 KB, `Spatial` 4 KB) | 11 |
| Individuals (60k, as today) | 9 |
| Overlay | ≤ 1 |
| Painter: country picture (896² on a phone, 1,792² on desktop) | 3.2 |
| Painter: district pictures | 16 |
| Painter: street chunk pictures | 40 |
| Timelapse at district resolution (6 KB per hour × 720 h) | 4.3 |
| **Total** | **about 145-150** |

  That leaves about 100 MB of headroom for generation (the coarse height grid needs about 13 MB while it is being built).
- **Memory budget, desktop:** chunk cache 4,096 (80 MB), paint cache 80 MB, country picture 12.8 MB; about 260 MB in all.
- **Cache sizes** come from a new `Platform.maxMemory()`: about 3 KB of chunk cache per MB of heap.
- **Generation time:**
  - A new game (coarse layer only): 3 s or less on desktop, 8 s or less on a phone.
  - One chunk: 1.5 ms or less on desktop, 5 ms or less on a phone. Tiles take bilinear coarse height plus 2 octaves of detail noise, about 3 noise evaluations per tile instead of today's about 13.

## 2. Architecture

### 2.1 Coarse national layer (`Nation`, built by `NationGen`)
- **Land cover and height** on the 16-tile grid.
  - Land cover values: deep, water, sand, grass, forest, farm, rock, urban, park, base, airfield.
  - Terrain noise frequencies are set in km, not as `size/1024` as in `terrainRow` today. Tile-level terrain is the bilinear coarse height plus detail noise, so the country picture and the streets agree.
- **Rivers and coast.**
  - Rivers are polylines with a width (descending the coarse height), and the river bias carries over from the presets.
  - Lakes and the coast come from the height thresholds.
- **Settlements.** Placed on the coarse grid using today's `landFrac`/`waterNear` scoring, but with the realistic radii above.
  - Each has a centre and sub-centres; the capital has 3-5 nuclei.
  - Each has a district raster at sector resolution: one byte for the district type (CORE, DENSE, MIXED, SUBURB, INDUSTRIAL, PARK, CAMPUS, RURAL) and one byte for density class, from the distance to the nuclei plus noise, dropped on water.
  - **Residents per sector are worked out once here and are authoritative.** Population becomes `Sectors.home`. Chunks only share each sector's count out among its buildings, so the count never depends on what has been generated.
- **Sector summaries** (one byte each): water, deep water, forest, rock, road, farm and built-up shares. These replace the init-time tile scans in Ecology, Navy, Infrastructure and Game.
- **Facilities.**
  - Counts follow population: police stations 1 per about 40k (about 30 in the capital), hospitals 1 per 100k, fire stations 1 per 30k, plus the existing single facilities.
  - Each reserves a lattice block (a superblock if needed). Its building rectangle and yard are worked out from the block with the existing `facility()` layout and stored in the existing `Region` building arrays. These "national buildings" have small ids, so `Facility.building`, `f.x/f.y` and `f.node` stay valid. No `snapToOpenGround` is needed: the template puts the yard on LOT.
- **Large sites** (army bases, prisons, airfields, naval base, port): a list of rectangle-painting operations plus national buildings plus graph edges. This is today's `layoutBase`/`layoutAirfield` code turned into data.
- **Highways and railways.**
  - A* on a 64-tile cost grid (448² cells on the Country map; today's `cellCost`), smoothed, stored as HIGHWAY edges in `roads` and as `rails`.
  - Bridges are flagged where an edge crosses water.
  - Entry points stay as they are.
- **Street lattice graph** for every settlement, built directly from the lattice formula (no tiles):
  - Streets across water only on avenues, as bridges (the existing rule in `street()`).
  - Superblock merges by hash.
  - Duplicate edges avoided through canonical lattice ids, plus a `HashMap<Long,Integer>` fallback.
- **States, grids and networks:** today's `states()` and `network()`, unchanged.

### 2.2 Chunks on demand
- **`ChunkGen.generate(nation, cx, cy)` is a pure function.** All randomness comes from `Rng.hash(seed, chunk, salt)` or a block's own stream `Rng(hash(seed, settlement, k, j))`, never from a shared Rng. Use `StrictMath` for trigonometry in generation, so desktop and Android build the same chunk.
- **Steps:**
  1. Terrain tiles.
  2. Rivers and lakes.
  3. Draw every road edge from the per-chunk edge bucket (width from `ehalf`, bridge tiles over water) and rails.
  4. Site rectangle operations and national buildings that touch the chunk.
  5. Each lattice block in the chunk:
     - facility-reserved blocks: LOT/PLAZA ground under the national building;
     - otherwise `fillBlock` (existing logic, district type from the sector raster);
     - lots under a highway corridor are dropped.
  6. Farmhouses in FARM cover, by hash.
  7. Doors (today's `World.doors` per building).
  8. Nav.
  9. Share the sector's residents among residential buildings by floor space, with the largest remainders rounded up.
- **Building ids:**
  - National buildings: 0..N-1 in `Region`'s arrays.
  - Chunk buildings: `0x40000000 | chunk << 12 | local`.
  - Accessors replace direct `r.bx[b]`: `bx(id)`, `bkind(id)`, `bres(id)`, `doorX(id)`.
- **`ChunkCache`:**
  - LRU with pinning.
  - A background thread prefetches around the camera and along vehicles' routes. It only fills the cache.
  - Overlays are applied on the game thread when a chunk is first used (the overlay version is checked).
  - If two threads generate the same chunk, the results are identical; a per-chunk in-progress state with a wait avoids the waste.
- **Determinism rule:** the simulation reads only "sim-active" chunks, and that set is a deterministic function of simulation state.
  - The set is recomputed once a second at the start of `secondly`, single-threaded. It contains the chunks of hot or camera sectors plus a 1-chunk ring, the chunk of every free individual and ground vehicle in an active area plus a ring, chunks with building fires, and chunks spawned into.
  - A chunk entering the set is fetched or generated synchronously at that moment. That only costs time, never changes the result.
  - The parallel think and move phases never generate. `Audit` checks that every free individual stands in an active chunk.
  - A deterministic priority applies the cap (individuals first, then hot sectors, then the camera).
  - Chunks leave the set after 10 quiet seconds.
  - Rendering reads the cache directly and never affects the simulation.
- **Overlay** (saved): per chunk, sparse tile edits (packed local index plus tile) for barricades, craters and blown bridges, and a bitset of destroyed buildings. Rubble is redrawn from the bitset, about 4 B per destroyed building, not one entry per tile. Blown bridges stay as `ealive` on the graph.

### 2.3 Routing on two levels
I recommend one road store with two-level search, rather than separate per-chunk street graphs.

Why one store: 155k junctions is 16 MB, every road edit (`ealive`, the barricade penalty) has one home, and vehicles' `int[] route` and `roads.nx[n]` stay as they are.

- **Level 1:** highways, arterials and avenues, with their own compact adjacency (about 20k junctions).
- **Level 2:** minor streets.
- **Search:**
  - Trips under about 3 km: plain A* with a node budget.
  - Longer trips: a bounded Dijkstra over level 2 from each end to collect up to 8 level-1 entry points with their costs, then a multi-source, multi-target A* on level 1, then stitch the route together.
- **Fixes:**
  - Duplicate check through a hash.
  - Node buckets at 2,048 px.
  - Edge buckets per chunk (about 2.4 MB) for drawing roads into chunks and for bounded `closeBarricadedRoads` (barricades read from the overlay, not from tiles).
- **Fallback:** if the Large map's graph goes over 25 MB, minor-street adjacency can be worked out from the lattice formula without storing it. Keep that as a hook only.

### 2.4 Scent field and foot paths only in active areas
- **Paging:** `Region.nav`, `Flow` (dist/stamp/step), `NavPath` (g/prev/stamp), `Spatial` (16×16 cells of 64 px per chunk, plus blocks) and the `Vehicles` grid are all paged per active chunk through a page table the size of the chunk grid (`int[200,704]`, 0.8 MB).
- **Flow:** the BFS crosses page borders through the table, and inactive pages count as blocked (deterministic, because the active set is deterministic). Distances from a BFS are unique, so `Flow` gives the same answers as today wherever every chunk is active.
- **`Spatial`:** clears only the pages and sectors it touched; no `Arrays.fill` over the whole map.
- **`NavPath`:** paths are searched only through active pages. If the goal lies beyond, the squad heads for the nearest frontier cell and plans again as pages activate.
- **Vehicles:** a vehicle with no active chunk near it goes into "transit". It advances along its route at a national road speed with no collisions, and turns back into a normally driven car when it enters an active chunk. This also fixes response times (see 2.8).

### 2.5 Sectors at the new size
- **Count:** Country map 200,704 sectors, of which about 45-60k have anyone living in them.
- **Districts:** about 3,136 (8×8 sectors), each with sums and an awake flag. A district is awake if any sector in it has dead indoors, infected, a hot timer, panic, fires, a horde arriving, the camera, or a neighbour that is awake.
- **The once-a-second work runs over awake districts' sectors only.**
  - On today's maps, "awake" can be made exactly conservative, so outcomes don't change.
  - `drift`, `cam`, `migrate`, `spill`, Ecology hunger and nests, Society holdouts, Director peaks, Navy coastal, Dispatch, Recovery and Engineers all switch to awake lists.
- **Per-settlement sector lists** (`int[][]`) with running totals replace every O(settlements × sectors) loop.
- **`Sectors.townDir`** becomes a multi-source BFS on the sector grid instead of a 25×25 scan per sector.
- **Hordes** pick targets from district sums: the nearest awake or populated district, then the best sector inside it.
- **Timelapse and heatmaps** sample at district resolution on national maps (6 KB per game hour; every 3 hours after day 3).
- **Estimated cost per update (desktop; phone about ×3.5):**

| Situation | Cost per update | Fast-forward, one game day (144 steps) |
|---|---|---|
| Quiet country (district scan 3,136 × 20 ns, plus settlements) | about 0.3 ms | 45 ms |
| War in the capital (about 5k sectors awake) | about 2.5 ms | 0.36 s desktop, about 1.2 s phone |
| Whole country collapsing (60k awake) | 27 ms flat; 8 ms with staggering | 1.2 s desktop, about 4 s phone |

  Staggering: in fast-forward, quiet-but-awake districts update every 4th step with 4× dt, in a phase fixed by district id. This is deterministic.
- At 1x the update runs once a second, within the 28 ms frame budget.

### 2.6 Drawing
- **Country view** (zoom below about 0.012, with `minZoom` derived from the map size: Country about 0.0023):
  - Coarse land cover picture, 1 pixel per cell (896² on phones, 1,792² on desktop).
  - District density tint from a sector-resolution picture.
  - Rivers, highways and rails as vector lines.
  - Settlement and facility symbols and labels.
- **District view** (0.012-0.2), new in `MapPainter`:
  - 512-tile (2 km) pictures at 1 pixel per 2 tiles (256 KB each; budget 16 MB).
  - Painted from coarse cover plus graph edges by kind plus lattice blocks filled with roof shades by district type and density.
  - Needs no chunk generation.
- **Street view** (from 0.2): today's 32-tile pictures at 4 levels of detail, painted from cached chunks.
  - A missing chunk shows the district picture and is queued for the background thread.
  - The camera-detail threshold stays at 0.3, so at most about 60 sim-active chunks come from the camera.
- **New Game preview:** painted from the coarse layer only.

### 2.7 Things you can tap
- **Settlements:** sheet as today, plus districts and population.
- **Districts:** new; name, people, dead.
- **Facilities:** nearest symbol at country and district zoom; at street level, a national building opens its facility sheet.
- **Buildings:** new `inspBuilding`, an encoded id, for a building in a loaded chunk. Shows kind, floors, residents, and how many are sheltering, estimated as `popIn[s] × bres/home[s]`.
- **Lookup:** O(1) through `sec.settlement[]`, plus a bucket list for about 1,000 facilities.

### 2.8 Timing and the rules in content.ini
National movers get speeds in km per game hour (1 km/h = 66.7 px per game second at 4 m tiles), stored as content.ini rules with a `@national` override, so today's maps and the balance test keep today's values until the switch.

| Mover | Proposed | px/s | Crossing the 115 km Country map |
|---|---|---|---|
| Horde | 2 km/h | 133 | 58 h, the same pacing as today |
| Refugees on foot | 3 km/h | 200 | – |
| Refugees by car (jammed roads) | 8 km/h | 533 | – |
| Convoy | 30 km/h | 2,000 | 3.8 h |
| Train | 60 km/h | 4,000 | – |
| Formation, road march | 20 km/h | 1,333 | – |
| Formation, tactical | 4 km/h | 267 | – |
| Ship | 25 km/h | 1,667 | – |
| Plane | 400 km/h | 26,700 | – |
| Vehicle in transit inside a city | 25 km/h | 1,667 | – |

- The literal 11 and 28 in Strategic become rules.
- Police response times stay about what they are now, because station density becomes realistic (stations 1-1.5 km apart, about 40 game minutes as today) and long drives go through transit.
- **Distances become km rules:**

| Rule | Today | Today in metres | Proposed |
|---|---|---|---|
| `trainOver` | 2,500 px | 625 m | 20 km |
| `troopTrainOver` | 4,000 px | 1 km | 30 km |
| `airliftDistance` | 7,000 px | 1.75 km | 60 km |
| Horde sensing (code) | 24 sectors | – | 6 km |
| Horde merge (code) | 200 px | – | 500 m |

  The `nearestNode` search radii in code also scale with the map.
- **Per-sector rules × 4 for the larger area:** `indoorDensity`, `sectorSpill`, `hordeSize`, `nestMin`, the emergence caps (40/12/28) and the bite floor. The migration rate is halved so the mass moves as fast as it does today.
- **Evacuation:** capacity per highway exit (`evacPerHourPerExit` ≈ 4,000), so a capital with about 8 exits moves about 32k people an hour.
- **Unchanged:** time-based rules, and street-level walking and driving speeds.

### 2.9 Saves, replays, determinism
- **Save version 5.** Version 4 still loads, as today's maps.
- **Contents:**
  - Settings of the nation (size, preset, seed).
  - The overlay.
  - Floors of national buildings.
  - Road `ealive`.
  - Sectors sparse: the fields that differ from their starting values, with runs of zeros compressed.
  - Only the active `Flow` pages.
  - Everything else as now.
- **Expected size:** 0.5-2 MB typically, about 5 MB at worst with 60k individuals.
- **Replays** stay events-only, a few KB, and rebuild through the coarse generator.
- **Exactness rests on:**
  1. chunk generation being pure;
  2. the active set depending only on simulation state;
  3. the background thread filling the cache only;
  4. no logic iterating a HashMap.
- **New tests:** a replay and a save on a national map; chunks generated in a scrambled order on 1 and 4 threads give the same bytes; after the cache is evicted and refilled, the state hash is identical.

## 3. Phases
Every phase merges with `./test.sh` green and a working APK. National maps stay off the New Game menu (Probe and tests only) until P10, so the game ships after every phase.

**P0. Probes and baselines** (low risk)
- Files: `desktop/src/Mem.java` (memory by component), `Probe.java` (cost of the once-a-second update, steps of fast-forward per second), new `desktop/src/ScaleProbe.java`, and a guard test in `Tests.java` for the once-a-second cost.
- Verify: numbers match section 0.

**P1. Chunked storage of tiles** (low-medium risk; `t()` is on hot paths, so watch the 20k-dead perf test)
- Done: `Region.tile` and `bldAt` become pages in a `ChunkStore` (new `Chunk.java`, `ChunkStore.java`), all chunks resident for today's maps.
- Done: add `Region.sect` (still 32) and building accessors.
- Files: `Region.java`, `RegionGen.java` (its 15 direct `r.tile` writes go through a raw writer), `SaveIO.java` (writes the same bytes in the same order), and `Game.java` line 1016 (the land mask).
- Verify: `checksum()` identical, every test green, perf test within 5%.
- As built: `ChunkStore` holds per-page `byte[]` tiles and `short[]` buildings in exactly `Chunk.tile`/`Chunk.bld`'s layout and encoding (Region's own buildings stored as `-(id + 1)`, as Chunk stores national buildings; so Region now allows 32,768 of them). Page index is `cy * pw + cx`, which equals `Chunk.index` on a national map, and `store.attach(chunk)` makes a generated Chunk a page as it is (no copy). There is no separate `Chunk` page class: today's maps hold plain arrays, with no Chunk objects. `Region(w, h, seed, preset, sect, allPages)` with `allPages = false` leaves pages unresident and, until P2 pages it, gives no `nav`. `building(tx, ty)` returns Region ids or encoded chunk ids, and `bx(id)`...`bset(id)`, `doorX/doorY(id)` (px; World hands Region its door arrays) and `chunkOf(id)` read either kind.

**P2. Active-area paging** (medium risk)
- Done: `ActiveSet`; paged `nav`, `Flow`, `NavPath`, `Spatial` and the `Vehicles` grid, with clearing of touched cells only.
- Files: new `ActiveSet.java`, `Region.java` (nav, `lineOfSight`), `Flow.java`, `NavPath.java`, `Spatial.java`, `Vehicles.java` (grid), `World.java` (step order), `SaveIO.java` (flow pages), `Audit.java`.
- On today's maps the set is "everything"; a test forces sparse mode on a 1024 map.
- Verify: the 1-vs-4-threads, save, replay and "everyone accounted for" tests in both modes.
- As built:
  - **`ActiveSet`** (`World.act`) owns a slot per active page: `slotOf[page]` (-1 inactive) and `pageOf[slot]`. Dense (today's maps): every page active, page p is slot p, never recomputed. Sparse (`ActiveSet.sparseDefault`, read by `new World`; tests set it, P7 should make national maps sparse by construction): recomputed by `update()` at the start of `secondly` (not in strategic fast-forward; also on a new game's first step). Wanted, in priority order until `limit` (512): every free individual's chunk, then a ring round each; chunks with a building fire (`Fires.burningAt`); hot sectors' chunks plus a ring; the camera's chunks plus a ring; then a ring round each ground vehicle that is moving or crewed and stands in a chunk already wanted this update. Chunks leave after `QUIET` = 10 game seconds unwanted (page order); entering ones get a slot (free stack, else a new one; users' arrays grow by doubling). `touchPx(x, y)` makes a chunk active at once; `Ents.spawn`, `alight` and `rise` call it, and so does a vehicle in transit arriving or replanning. `Source.fetch/release` is the hook for P7's ChunkCache (null today: every page is resident).
  - **Paging:** `Flow`, `NavPath`, `Spatial` and `Vehicles` implement `ActiveSet.Paged` (`slotsGrown`, `slotAssigned`) and keep flat arrays indexed by slot. Flow/NavPath cells are `slot << 10 | y << 5 | x` (32x32 a page; in dense mode slot = page, so no table load), with an interior fast path and page-table lookups only on a page's edge; an inactive page is blocked. In dense mode `Spatial` and the vehicle grid keep the old row-major layout (`1 + cy * cw + cx`, never paged: paging them cost about 7% in the 20k test). In sparse mode `Spatial` cells are `cellOff + (slot << 8 | ...)` (16x16 a page) and blocks `blkOff + (slot << 4 | ...)`; the extra row and column past the map (positions were always clamped into `px/64 + 1` cells) are fixed "edge" cells at the front of the arrays, and index 0 is the shared empty cell of every inactive page. Callers find cells only through `sp.cell(cx, cy)`, `cellOf`, `cellX/cellY` (ArmedAI, CivAI, Combat, Dispatch, Undead, Vehicles, WorldView were changed from `cy * cw + cx`). `Spatial.build` clears only the cells, their blocks and the sectors it touched. The Vehicles 128 px grid is paged the same way (`gcell`, 8x8 a page).
  - **Navigation cells** live in `ChunkStore.nav` (32x32 per page, `Chunk.nav` on an attached chunk), so they follow residency, not activity. `Region.nav` is gone: use `navAt`, `navOpen`, `navPage`, `navCopy` (tests). `lineOfSight` returns false across a page that isn't resident.
  - **NavPath:** a goal on an inactive page sends the search towards it and stops at the first frontier cell popped (one with an inactive neighbour); the path then ends there, without the goal point. `straightWalkable` treats inactive pages as blocked. Squads replan on their timer, by when the pages round them are active.
  - **Transit:** a `V_DRIVE` vehicle whose chunk isn't active moves junction to junction along its route at `Vehicles.TRANSIT_SPEED` (1,667 px/s = 25 km/h; becomes a rule in P9) with no collisions or barricade checks, replans if a road on its route is dead, and drives normally again on the first step it stands on an active page. Transit vehicles are in no grid cell. `transitSteps` counts it.
  - **Saves:** a dense game still writes version 6, byte for byte as before (the flow field is written row by row from `atCell`). A sparse game writes version 7: the set (`limit`, `fresh`, then each active page with its last-wanted second) right after the header, and the flow field as 1,024 shorts per active page in page order. `SaveIO.load` makes the World sparse from the version and restores the set before anything else. Versions 3-6 load as before.
  - **Audit:** every free individual stands in an active chunk.
  - **Evidence:** `Audit.hash` at every 10 s and the compressed save's SHA-256 identical to before on four scenarios (768 sandbox and lab_leak with a reload, 1024 with 20k dead, 1024 with 2,500 dead over 120 s). Sparse mode on the last of these gives the same hashes as dense for the first 100 s, then differs (the set starts to matter), with about 80-100 of 256 chunks active. Tests: the four tests above run again in sparse mode at 1024, plus "sparse: chunks follow everyone, leave when quiet, and cars cross the rest in transit".
  - **Perf and memory:** the 20k-dead test on one thread, both builds alternating in one JVM (separate class loaders, 12 measured rounds each, two runs with the order swapped): 1.011 and 1.009 of before. `Mem` (dense): 56 MB at 2304 and 93 MB at 3072, as before (the navigation grid now counts under `r.store`). Sparse, the same new game after 40 steps with no camera: 20 MB at 2304, 34 MB at 3072 (Flow, NavPath, Spatial and the vehicle grid hold only the active pages; tiles stay fully resident on RegionGen maps).
  - **For P7:** Sectors.connectivity reads `navOpen` over the whole map (needs the coarse layer); `Vehicles.arrive/findSlot` reads tiles up to about 400 px round the arrival point, but only the arrival chunk is touched; `Spatial` and the vehicle grid assume a square map; replay files don't record the mode (they follow `sparseDefault`); P4's next save version has to carry both the version-7 set and its own overlay.

**P3. Sector scalability**, split three ways:
- **P3.0:** new `SectorIndex.java` (per-settlement lists, districts, awake lists) plus hooks in `Sectors.java`.
- **P3a:** `Sectors.java`, `Ecology.java`, `Navy.java`, `Fires.java` (the `secFires` loop), `Director.java`, `DirectorScreens.java`, `Recovery.java`, `Engineers.java`, `Command.java`; also `Region.SECT` → `r.sect` in these files.
- **P3b:** `Strategic.java`, `Society.java`, `Logistics.java`, `Infrastructure.java`, `Media.java`, `Roles.java`, `Setup.java`, `Dispatch.java`, `Forces.java`, `Stats.java`, `Military.java`, `Vehicles.java` (`secCars` only, after P2), `WorldView.java` (SECT only).
- Risk: medium, though outcomes can be identical.
- Verify: `Audit.hash` identical to before on fixed seeds (add a golden-hash test at the start of P3), balance band unchanged, fast-forward time at 2304 no worse.

**P4. Overlay saves (version 8 as built)** (medium risk)
- Files: new `Overlay.java`, `Region.java` (`set`, `destroyBuilding`, `rebuildNav` record edits), `SaveIO.java`.
- Verify: save tests, version-4 load test, and an assertion that the save is smaller.
- As built:
  - **`Overlay`** (`Region.overlay`, implements `ChunkCache.Overlay`) holds per page (= chunk index on a national map) a `Page`, made on the first edit: tile edits sorted by local index (`short` index, the tile now, and the tile it replaced, which is never saved); `gone`, a bitset of the chunk's own buildings brought down; `national`, the Region (national) buildings brought down that stood on the page, in the order they fell; and a `version`, raised by every change. An edit put back to the tile it replaced is dropped. A tile edited and later rubbled by its building coming down loses its edit at that moment, so applying a page in a fixed order (national rubble, chunk-building rubble, tile edits, then the page's navigation cells from its tiles by the `rebuildNav` rule) always gives the tiles as they were left. Rubble isn't stored per tile: it is drawn again on the tiles the building still holds (`bld`). Methods are synchronized (edits come from the game thread; `version`/`apply` also from `ChunkCache` callers).
  - **Recording:** `Region.set` (now a no-op when the tile doesn't change) and `Region.destroyBuilding` record; `World`'s constructor calls `r.recordEdits()` (one line), so generation writes freely. Every map-changing path already went through those two: Engineers walls, Undead smashing (rubble), `Strategic.blowBridge` (water; `ealive` stays on the graph and is saved as before), Society camps, Combat big bombs and air strikes (`destroyBuilding`), Fires collapse (`destroyBuilding`). No module file needed a change. `rebuildNav` records nothing (navigation follows from tiles) and now skips non-resident pages. `destroyBuilding` also takes an encoded chunk building id (rubble in its chunk, `bfloors` 0, the `gone` bit), and for a national building over a page that isn't resident it records the id there (`nationalOn`), so the page gets the rubble when it comes in. An edit to a resident Chunk page sets that chunk's `overlayVersion` to the new version (it already holds the edit), so `ChunkCache.get` doesn't regenerate it.
  - **Version 8** (`SaveIO.VERSION`; every save, dense or sparse, is written in it): after the header a `sparse` flag, then the v7 active set when sparse; the overlay instead of every tile (pages with anything on them: varint page gap, edits as varint index gaps plus a tile byte, national ids, `gone` words); `bfloors` (floors of Region's buildings) and `ealive` as before; sectors sparse (`popIn` against `home`, `firstZ` against -1, the rest against 0, floats as XOR of bits; each field as runs of zeros and values, varints; `SaveIO.runs/readRuns` are package-visible for modules to use), and Dispatch's `secIncident` the same way against -1; the scent field as pages (each active page in page order: a flag, and its 1,024 cells only if scent reaches it). Load regenerates the region, builds the World on it, then `Overlay.applyTo` (taking each edit's replaced tile from the fresh map) and rebuilds navigation as before. Versions 3-7 still read every tile; the overlay of such a game is worked out by `Overlay.diff` against a copy of the generated tiles taken before they are read, so it saves again as version 8 exactly.
  - **Fixtures:** `desktop/testdata/save-v6-dense.zcs` and `save-v7-sparse.zcs`, written by the code before this phase (768, seed 21, lab_leak, 400 dead painted, an air strike, a blown bridge, 30 s), join the v3 one. No v4 or v5 save exists to make a fixture from.
  - **Evidence:** existing tests (save round trips dense and sparse, autosave, damaged barricades, v3 load, replay at 768 and sparse 1024, 1 vs 4 threads dense and sparse) pass unchanged (95 of 95 green after merging P8 and P9a; the v6/v7 fixtures load with the "different rules" notice since P9a changed content.ini, and still carry on exactly). New: "save: the overlay holds every change to the map..." (1024 with barricades the dead break, a big bomb and a blown bridge: after a round trip every page's tiles and buildings, the navigation grid, the checksum, `Audit.hash`, and the overlay's own bytes are identical, it plays on identically, the raw save is smaller than the tile dump alone, and a barricade put back leaves no edit); "save: version 6 and 7 saves still load, play on, and save again smaller" (91 -> 66 KB and 90 -> 65 KB); "chunks: an edited chunk evicted, regenerated and the overlay applied comes back the same" (28,672 nation, a capital chunk: 12 barricades, an edit put back, a house's corner edited then the house destroyed, two chunk buildings and a national building destroyed; same `Chunk.checksum` after eviction; a national building destroyed while its chunks weren't in memory comes in as rubble).
  - **Sizes** (`ScaleProbe save`: lab_leak, seed 7, 2,000 dead painted at the origin, 40 s, an air strike, 20 s; KB raw / compressed):

| Map | Before, dense (v6) | After, dense (v8) | Before, sparse (v7) | After, sparse (v8) |
|---|---|---|---|---|
| 1024 | 2,073 / 216 | 693 / 180 | 1,693 / 217 | 629 / 181 |
| 2304 | 8,822 / 402 | 1,127 / 244 | 6,525 / 399 | 1,071 / 243 |
| 3072 | 15,216 / 509 | 1,285 / 253 | 10,805 / 500 | 1,224 / 251 |

  - **What's left in a save:** at 3072 (9,216 sectors) about 470 KB of the raw ~1,250 KB is modules writing dense per-sector arrays: undead 129 KB, director 93 KB, ecology 92 KB, society 48 KB, recovery 38 KB, logistics 28 KB (2,000 individuals, the rest). At 200k sectors these grow about 22 times (about 10 MB raw): P3/P7 should move them to `SaveIO.runs`.
  - **For P7:** give `Region` the national buildings (its `bx`... arrays) so `destroyBuilding(id < bCount)` works, as the chunk test does by sharing the Nation's arrays; call `cache.setOverlay(r.overlay)` and attach the chunk `get` returns (the cache must not hand out a regenerated copy of a chunk that is attached: `edited()` keeps the attached chunk's `overlayVersion` current so it doesn't). Overlay page = `ChunkStore` page = chunk index. Barricades are tile edits, so a bounded `closeBarricadedRoads` can read them from `Overlay.edits(p, ...)` instead of sampling tiles. Undead's damage map is keyed by map tile index (fits an int at 28,672; a chunk evicted with damage on it keeps the damage, which is fine as long as it is read only on active chunks). Sectors' `home` must stay the starting `popIn` (the sparse sectors depend on it). Combat's blast still reads `w.r.bres[b]` and `w.doorX[b]` directly.

**P5. NationGen coarse layer** (medium risk; new code)
- Files: new `Nation.java`, `NationGen.java`, plus `RoadGraph.java` (hash duplicate check, edge and node buckets, level-1 adjacency, two-level route).
- Tests:
  - same seed gives the same checksum;
  - capital 1.0-1.4M; cities 150-450k;
  - every settlement reachable;
  - facility counts in their bands;
  - built in under 3 s at 28,672 on desktop;
  - nation memory under 40 MB;
  - two-level route cost within 10% of plain A* on samples.

**P6. Chunk generation and cache** (medium-high risk)
- Files: new `ChunkGen.java`, `ChunkCache.java`.
- Tests:
  - bytes identical in any order or thread;
  - road seams line up across chunk borders;
  - residents per chunk sum exactly to the sector's;
  - no building on water;
  - p95 under 1.5 ms per chunk on desktop;
  - evict, regenerate and apply the overlay, and get the same result.

**P7. National maps wired into the world** (high risk; the critical path)
- Done: `Region` backed by `ChunkCache`; sectors start from the nation's residents; doors and building lists per chunk; connectivity from the coarse layer plus roads.
- Done: Fires on two tiers (counted fires per sector, building fires in active chunks) with a sparse map in a fixed order; Society looting picks a sector, then a building.
- Done: air strikes generate the chunk synchronously; init scans replaced by sector summaries; `Setup.newGame` picks the generator by map size.
- Files: `World.java`, `Region.java`, `Sectors.java` (constructor), `Fires.java`, `Society.java`, `Combat.java`, `Engineers.java`, `Strategic.java` (bridges), `Navy.java`, `Ecology.java`, `Infrastructure.java` (init), `Setup.java`, `SaveIO.java`.
- Verify: on a small national map (e.g. 8192) the national versions of the sim, save, replay and audit tests pass; Probe at 28,672 holds memory under 160 MB.

**P8. Drawing and tapping** (medium risk)
- Files: `MapPainter.java` (country and district levels, chunks from the cache, prefetch), `Camera.java` (`minZoom` and flights from the map size), `WorldView.java` (vectors, facility symbols, district labels, chunk buildings), `Game.java` (preview, building and district inspector, zoom thresholds).
- Verify: UiShot at the five zooms on 28,672; frame time on desktop; a test that a tap opens the right building.
- As built (drawing half; tapping and the Game inspector are still to do, with P7):
  - The renderer works from a `Nation` plus cached chunks through `NationPainter.Source` (`nation()`, `chunk(cx, cy)` which never generates, `want(cx, cy)` which queues background generation); `NationPainter.source(nation, cache)` wraps a `ChunkCache` (`peek` and `prefetch`). `new WorldView(source, pf)` draws a nation with no `World` (`w == null`): ground, lines, buildings, names. With a `World` and `np != null` (P7), `ground()`, `smoothLines()` and `buildings()` take the national paths too. Today's maps are untouched: `Shot` renders pixel-identical PNGs at five zooms before and after.
  - New `NationPainter.java`: the country picture (a pixel per coarse cell, or per two when `Platform.maxMemory()` (new) is under 768 MB: 1,792² desktop, 896² phone), a sector-resolution density tint, and district pictures (512 tiles) at three levels instead of one: 8, 4 and 2 tiles per pixel (64², 128², 256²; from zoom 0, 0.02, 0.045), since at 0.012 a phone screen shows about 275 districts, which 256 KB pictures can't hold in 16 MB. One LRU cache of 16 MB for all three. Every level paints the same ground: the coarse water as `ChunkGen.terrain` makes it (river corridors back to land, the same wobble noise, evaluated on a shared 4-tile lattice with each noise corner hashed once), land cover under the same jittered point with `landTile`'s tile kinds (copied) in `MapPainter.GROUND` colours, water shaded by distance from the shore as `MapPainter.waterColor`, rivers at width, highway verges, roads by kind from the per-chunk edge buckets (bridges over water), railways, site operations, facility blocks, national buildings, and each lattice block filled by a cheap hashed layout per district type (`blockTile`: plazas and towers, perimeter flats round courtyards, houses in gardens, sheds on yards, lawns...), roofs from ChunkGen's palettes darker at their edges. The country picture uses per-cover averages and a per-sector average of the same block layouts and street share, so the colours hold from country to street.
  - `MapPainter(source, pf, countryImage)`: the same 32-tile pictures at four levels, reading tiles through a 3x3 window of cached chunks (`t()` dispatches; on today's maps it is `r.t()`); a picture whose chunks aren't all cached isn't painted, the chunks are `want`ed, and the district picture shows. Lane markings use the edge buckets. `paintNs` counts painting time.
  - `WorldView.groundNation`: country picture; district pictures fade in over 0.0102-0.0138; street pictures fade in over 0.2-0.25 (chunks in view are prefetched from 0.12, nearest first); the tint fades out over 0.0108-0.0168; a level fully covered by the one above isn't drawn. The paint budget counts painting time only. Buildings from cached chunks (and the national buildings in them, via `ChunkGen.index(n).nats`) go through the existing building code, refactored into `drawBuildings` over gathered arrays; on a national map they rise out of the roofs over 0.22-0.34 (lean and shadows scaled) so nothing pops. Rivers and inter-town avenues are vectors in the country view, fading as the districts come in; highways and railways stay vectors below 0.45 as today. `nationLabels` places names and facility icons through the existing `place()` collision pass (capital and cities always; towns of 15k+ and sites from the country view; all towns from 0.0045; villages from 0.022; power stations, bases, prisons, airfields and ports always; police and hospitals from 0.025; the rest from 0.045, fire, fuel, water, telephones, food, depots and labs from 0.06).
  - `Camera`: `minZoom` was already `fitZoom() * 0.92` of the map (0.0022 on the Country map at 1080 px). On maps of 8,192 tiles and more, a flight that would cross more than the screen shows (or change zoom by more than e^2.5) is an arc: log-zoom dips so both ends fit half way, the ground moves at an even screen pace (position by the integral of 1/zoom), 0.8-3.2 s. Short hops and today's maps keep the old glide.
  - `Game`: the New Game preview is built with `NationGen` for a size in `Nation.SIZES` and painted by `NationPainter.preview` from the coarse layer only (280 ms on desktop at 768²); dead code until P10 puts the sizes on the menu.
  - Desktop tool `NationView` (outDir [w h density] [seed] [size]) drives WorldView over a Country nation: the five zooms, a smooth zoom from the whole country to a street corner, a cross-country flight, the fade bands side by side, frame times and district picture costs. New test "drawing: a real-scale map at every zoom" (pure district pictures, no chunk generated for them, street pictures from background chunks).
  - Measured (desktop, 2 cores, Java2D headless, 1080x2400 at density 2.75, NationView seed 3): district pictures, warm, 2.1 / 6.5 / 8.3 ms in the capital (64² / 128² / 256²), 0.5 / 1.5 / 6.5 ms elsewhere; country picture and tint 0.23 s at startup; New Game preview 0.22 s. Median frame, everything painted: country 67 ms, region (0.0065) 119, district (0.035) 69, town (0.11) 69, street (0.5) 116 ms; without the full-screen vignette every view draws (today's maps too; 35-40 ms in Java2D alone) 29 / 89 / 30 / 31 / 78 ms. Nearly all of it is Java2D's software scaling and compositing of screen-sized pictures (the magnified country picture and the tint at the region zoom, the buildings at street zoom); on Android the canvas does this on the GPU. A 97 km flight from a street corner: 3.2 s, rising to 0.0022, slowest frame 171 ms while painting on the way.
  - Left for P7 and the tapping half: chunk overlays (rubble) in the street pictures (`peek` returns chunks as generated) and `MapPainter.refresh()` for national maps; the building and district inspector; Game zoom thresholds for national maps; night lights on national maps.

**P9. Rules and timing** (medium-high risk; balance)
- **P9a:** `Content.java` (rule lookup with `@national` overrides, km/h and km helpers), `assets/content.ini`, speed lookups in `Forces.java` and `Logistics.java`.
- **P9b:** after P7: vehicle transit in `Vehicles.java`; horde and refugee speeds and distances in `Strategic.java`; `Navy.java`; per-area rules in `Sectors.java` and `Ecology.java`.
- Verify: today's maps unchanged; Balance on national maps over 6 seeds × 3 outbreak sizes.
- As built (P9a, with the literals of the P9b files turned into rules too, so P9b is tuning and the remaining mechanics):
  - **`Content`:** a `[rules]` key written `key@national` goes to a second map; `rule(key, def, national)` returns it on a national map, else the plain key, else `def`. `rule(key, def)` is unchanged (plain). Any other `@` suffix is refused. A value may carry a unit: `"2 km/h"` → `Content.kmh` (× 4,000 / 60 = 66.7 px/s), `"20 km"` → `Content.km` (× 4,000 px), `"500 m"`. `plainRule(key)` and `nationalRuleKeys()` are for tests. `rulesHash` still hashes every line, so it changed (a save from before P9a loads with the "different game rules" notice).
  - **`World.national`** (final) is `r.sect != Region.SECT`, so a Region built with chunk-sized sectors is national without P7 setting anything; `World.rule(key, def)` is `c.rule(key, def, national)`. Every lookup below goes through `w.rule`.
  - **Rules** (plain value = the old literal, code default the same; national from section 2.8):

    | Rule | Where | Today | National |
    |---|---|---|---|
    | `hordeSpeed` | Strategic (was 11) | 11 px/s | 2 km/h (133) |
    | `hordeSense` | Strategic (was 24 sectors), px ÷ `Tile.SIZE * r.sect` | 12,288 px | 6 km |
    | `hordeMerge` | Strategic (was 200) | 200 px | 500 m |
    | `refugeeSpeed` | Strategic column (was 28) | 28 px/s | 3 km/h |
    | `refugeeCarSpeed` | not read yet (P9b splits columns) | 28 | 8 km/h |
    | `convoySpeed`, `trainSpeed` | Logistics, Forces | 35, 90 | 30, 60 km/h |
    | `planeSpeed`, `shipSpeed` | Logistics | 500, 25 | 400, 25 km/h |
    | `transitSpeed` | Vehicles (was `TRANSIT_SPEED`, kept as the default) | 1,667 | 25 km/h (1,666.7) |
    | `trainOver`, `troopTrainOver`, `airliftDistance` | Logistics, Forces, Navy | 2,500, 4,000, 7,000 px | 20, 30, 60 km |
    | `indoorDensity`, `sectorSpill` | Sectors | 10,000, 3,000 | ×4 |
    | `hordeSize`, `nestMin` | Ecology | 300, 400 | ×4 |
    | `biteFloor` (bites per 10 s) | Sectors (was `0.5f * dt / 10f`) | 0.5 | 2 |
    | `emergeDead`, `emergeFleeing`, `emergeStreet` | Sectors (were 40/12/28) | 40, 12, 28 | 160, 48, 112 |
    | `migrateRate`, `shambleRate` | Sectors (were 0.02/0.003) | 0.02, 0.003 | halved |
    | `formationSpeed` | Forces road march; default `f.t.speed` | national only | 20 km/h |
    | `formationTactical` | not read yet (no tactical move exists) | national only | 4 km/h |
    | `warshipSpeed` | Navy; default the destroyer's speed | national only | 25 km/h |
    | `airliftSpeed` | Navy; default the transport plane's speed | national only | 400 km/h |
    | `evacPerHourPerExit` | not read yet (P9b, per highway exit) | national only | 4,000 |

  - The code edits are line-local (`w.c.rule` → `w.rule`, a literal → `w.rule("…", literal)`), so P3 and P4 merge easily; float expressions keep their order (`0.5f * dt / 10f` became `rule * dt / 10f`, `d > 24` became `d > 12288f / 512`, exactly 24).
  - Still in code for P9b: the `nearestNode` search radii (Forces 700/2500, Navy airfield 5000/4000), the Strategic horde rethink scan (O(sectors), P3), and refugees split into foot and car.
  - **Evidence:** `Audit.hash` identical before and after at every checkpoint of: 768 lab_leak 1,200 steps; 1024 with 3,000 dead 1,500 steps; sparse 1024 with 2,500 dead 2,400 steps (cars in transit); 1536 hold_city, evacuate and protect, 3 days of fast-forward (hordes moving and merging); 2304 retake and hold_city, 9 days of fast-forward (22 formations, airlifts, 275 supply runs). `Balance.play` on 1536 (2,000 and 20,000 dead, seeds 1-2): identical results. Test "content: national rules override only on national maps, and today's values are the old literals": for every `@national` rule the plain value equals the old literal and the national one differs; national-only rules have no plain value; unit conversions; a 768 World is not national and reads today's values.

**P10. The switch** (medium risk)
- Done: new sizes in `RegionGen.SIZES` and the New Game screen, challenge codes, README and DESIGN; tests move to national sizes (balance band on the small national map); today's sizes kept as hidden test maps.
- Files: `Game.java`, `RegionGen.java`, `Tests.java`, `Balance.java`, docs.

**P11 (optional). Retire `RegionGen`**: move the remaining 768/1024 tests to small `NationGen` maps.

## 4. Parallel work and file ownership
- **Wave 1:** P1 ∥ P3 (P3.0 first, then P3a ∥ P3b) ∥ P5. The file sets are disjoint: P1 owns `Region`, `RegionGen`, `SaveIO` and one line of `Game`; P3 owns the module files listed; P5 owns new files plus `RoadGraph`.
- **Wave 2:** P2 (after P1) ∥ P6 (after P1 and P5; new files only) ∥ the rest of P3.
- **Wave 3:** P4 (after P2) ∥ P8 (after P6) ∥ P9a. Disjoint: P4 owns `SaveIO`, `Overlay` and `Region`; P8 owns `MapPainter`, `Camera`, `WorldView` and `Game`; P9a owns `Content`, content.ini, `Forces` and `Logistics`.
- **Wave 4:** P7 alone.
- **Wave 5:** P9b ∥ the remaining tapping work in P8.
- **Wave 6:** P10.

## 5. What breaks in the tests and how each phase keeps them green
- **Region tests at 768/1024** (loops over `bCount`, checksum): unchanged through P6; national versions are added in P5 and P6.
- **"country: a capital of a million" (2304) and "sectors belong to the town" (1536):** stay on the old generator until P10, then move to `NationGen` (the 15% tolerance holds better with sectors the same size as chunks and residents worked out up front).
- **Save tests:** pass through P1-P3. In P4, version 5 replaces the full tile dump and the full flow field; the round trip is re-checked, and a version-4 fixture keeps loading.
- **Director replay and 1-vs-4 threads:** unchanged on today's maps; P2 and P7 add sparse and national variants.
- **Balance band:** protected by the `@national` overrides until P10, when the bands are tuned again on national maps. This is the main risk.
- **Perf test (20k dead at 1024):** watch it in P1 and P2.
- **New `Audit` invariants:** every free individual in an active chunk; sector counts equal the nation's residents at the start.

## 6. Main risks
1. `Region.t()` indirection on hot paths (P1). Keep a direct page reference and an inlined index in the hot path.
2. Tile reads that slip outside the active set inside parallel phases (P2, P7). The `Audit` invariant plus a debug assertion in `Region` catch these.
3. Rebalancing (P9, P10).
4. Visible frame hitches when the camera jumps to unseen streets and up to about 60 chunks are generated at 5 ms each on a phone. Mitigations: district pictures stand in while the background thread prefetches around the camera and vehicles' routes.
5. Large country on low-memory phones: slim the per-sector arrays to 80 B or less, or offer the Large map only on desktop.

### Critical files for implementation
- /home/user/Zombie-City-Sandbox-2/src/core/com/instantaxtion/zcs2/Region.java
- /home/user/Zombie-City-Sandbox-2/src/core/com/instantaxtion/zcs2/RegionGen.java (and the new NationGen.java and ChunkGen.java beside it)
- /home/user/Zombie-City-Sandbox-2/src/core/com/instantaxtion/zcs2/Sectors.java
- /home/user/Zombie-City-Sandbox-2/src/core/com/instantaxtion/zcs2/RoadGraph.java
- /home/user/Zombie-City-Sandbox-2/src/core/com/instantaxtion/zcs2/SaveIO.java
