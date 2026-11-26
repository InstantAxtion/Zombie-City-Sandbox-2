# Zombie City Sandbox 2: design

How the sequel works, and how it answers the lessons written down in the first game's brief
([original-game-brief.md](original-game-brief.md), sections 14 to 16).

## The shape of it

| | First game | Sequel |
|---|---|---|
| Map | One city, 424-752 tiles | A region, 768-1536 tiles: a city (the old map's size), towns, villages, an army base, a prison, countryside |
| People | ~3,000 individuals | ~30,000-60,000 residents counted by the sector, plus up to 60,000 (setting) individuals where it matters |
| Crowds | Each zombie steers, pairwise separation | One scent field (breadth-first search) twice a second; the dead walk downhill on it |
| Threads | One | Parallel think and move phases, deterministic |
| Entity state | Booleans (dead, removed, listed), lazy sweeps | One location per slot, changed only by four methods |
| Behaviour versions | `if (civic >= N)` everywhere | None: saves are versioned, the rules aren't |
| Content | Code (40,000 lines of landmark drawing) | `assets/content.ini` for every type and scenario |
| Tests | 25, about 12 minutes, Android stubs | 16, about a minute, no stubs (the core has no Android code) |

## Code map

All in one package, `com.instantaxtion.zcs2`. The core (`src/core`) never touches Android.

| Area | Files | What they do |
|---|---|---|
| Content | `Content`, `assets/content.ini` | Weapons, units, zombie types, vehicles, scenarios |
| The region | `RegionGen`, `Region`, `RoadGraph`, `Tile`, `Noise`, `Names` | Terrain, rivers, settlements, street grids, blocks and lots, facilities, the army base and prison, highways (A* over the terrain, smoothed, bridged), farms; the road graph and its routing |
| The world | `World`, `Ents`, `Spatial`, `Flow`, `NavPath`, `Parallel`, `Rng` | Entity storage, the spatial grid, the scent field, foot paths, the worker pool, deterministic randomness, and the order of a step |
| Two tiers | `Sectors` | The counted population and dead, indoor infection, coming out onto the streets and going back in, migration |
| Behaviour | `ZombieAI`, `CivAI`, `ArmedAI`, `Combat` | Thinking (in parallel) and carrying it out (in order): bites, shots, blasts, deaths, rising |
| Forces | `Squads`, `Vehicles`, `Dispatch`, `Military` | Squads and their orders; driving on the road graph and flying; 911 calls, cordons; the army, gunships, tanks, air strikes, supply, outside help |
| The region-wide | `Strategic`, `Gov`, `News`, `Stats` | Facilities, power grids, ammunition, hordes, refugees, bridges, strains, goals; the alert level, trust, curfews, evacuations, rumours; the radio log; the numbers and the report |
| Player | `Tools`, `Setup` | Everything the player can do; building a new game and its scenario |
| Checking | `Audit`, `SaveIO` | Invariants and state hashes; saves |
| Presentation | `Gfx`, `GImage`, `Platform`, `Camera`, `MapPainter`, `WorldView`, `Ui`, `Game`, `Settings`, `Texts` | Drawing at every zoom, the interface, the screens |
| Sound | `Synth`, `Mixer` | Every effect and the score, synthesised; mixing |
| Android | `MainActivity`, `GameView`, `AndroidGfx`, `AndroidImage`, `AndroidPlatform` | The shell |
| Desktop | `DesktopMain`, `DesktopGfx`, `DesktopPlatform`, `Tests`, `Probe`, `Shot`, `UiShot` | Window, tests and tools |

## A step (1/20 s)

1. Once a second: sectors, dispatch, the army, strategic systems, government, statistics, squad morale.
2. The spatial grid (a counting sort into 64 px cells, the dead and the living in separate lists), and twice a
   second the scent field.
3. Squads decide where their members go (in order).
4. **Think, in parallel.** Each entity reads anything and writes only its own slot: a velocity and an intent
   (bite, shoot, grenade, board, go indoors, evacuate, claw, break in). Random numbers here are hashes of
   (seed, step, entity), so the answer doesn't depend on which thread ran it.
5. **Intents are carried out on one thread, in entity order.** That's where anything touches anyone else.
6. **Move, in parallel**, sliding along walls. Then vehicles, bodies and effects.

The test `sim: the same game on 1 and 4 threads` checks a state hash every 10 seconds.

## Two tiers: counted and individual

Each 512 px sector holds `popIn` (people sheltering), `inf` (the bitten who haven't turned), and `zIn` (the dead not
walking the streets as individuals). Every second, in every sector: the dead indoors bite the people sheltering
(slowly: they barricade), the infected turn, some of the infected go to the town's hospital, armed units present
clear buildings room by room, and the unwatched dead migrate towards people and light, or form a horde.

A sector is **hot** when individuals are needed: the dead near the living, armed units near the dead, the dead
next to a living neighbour, or the camera. Hot sectors let the dead out of buildings to the living, send people
running when the dead get inside, and (in view, by day, with no curfew) put ordinary people on the streets.
When a sector has been quiet for a while its individuals fold back into the counts. Police and soldiers always
stay individual. The test `sim: everyone accounted for` pans the camera across the city and checks that the
number of people is exactly the same afterwards.

The camera is therefore an input to the simulation, like a tap. A replay would record it.

## The dead as a fluid

`Flow` runs one multi-source breadth-first search over the 32 px navigation grid from every living person on the
map (distance 0) and every noise (gunfire, blasts, screams; distance 56 minus loudness), out to 56 cells. A
zombie with nothing in sight walks to its lowest neighbouring cell. Thousands of the dead find their way round
buildings to a fight for the cost of one search, instead of a path search or a steering check each. Nearby
targets are chased directly; separation looks at a sample of the same cell only. 20,000 individual zombies in
the city with its police run at about 2.5 ms per step on the 4-core desktop the tests ran on (the first game's
brief measured 15-19 ms for 20,000 on its own machine; not the same machine or the same fight, but the same scale).

## One owner for every entity

`Ents.loc` is one of NONE (free slot), FREE (on the map), ABOARD (in a vehicle), BODY. Only `spawn`, `board`,
`alight`, `die`, `rise` and `remove` change it, and each checks the state it expects and throws if wrong. A
vehicle's crew list and its crew's `loc` are changed in the same call. `Audit.check` verifies every invariant
(crews agree, squads agree, nothing off the map or NaN, the live count matches) and runs inside the tests every
five seconds of every outbreak. The first game's "people listed twice" and "police lost off the map" can't happen
silently here: they would fail the audit.

## Vehicles on a graph

Roads are a graph built during generation (junctions and the streets, avenues and highways between them, with
bridge flags). Vehicles route with A* on it (with per-edge penalties) and drive by steering at a point a little
ahead in their lane, queueing behind whoever is in front. A car stuck for 3.5 seconds squeezes past. They run down
the dead at speed and get mobbed when they stop among them. Blowing a bridge removes its edges: routes go round,
and the river becomes a line hordes can't cross (sector connectivity is recomputed).

## Saves version the format, not the rules

`SaveIO` writes everything the simulation depends on, including the scent field, noises and the dice, so a
loaded game continues exactly as the original would have (the test compares state hashes for 40 seconds after
loading). The region is rebuilt from its seed and the saved tiles (rubble, blown bridges) laid over it. There is
one save version and no behaviour gating. A save made with different rules (`content.ini` hash) loads with a note.

## Lessons from the first game, and where they went

- *The fight must be legible.* Everything can be tapped; squads and their orders, the alert level, the radio log
  with places, the report's turning point, the infection stain at a glance from high up.
- *Better behaviour, not artificial limits.* No caps on responders. Cars squeeze past jams instead of
  being held back; police switch to a perimeter rather than being recalled (recalling them made it worse in the
  first game).
- *Systems that add clutter get cut.* Each new system changes the outbreak in a way you can see: a dark town, a
  stopped fuel convoy, a refugee column, a blown bridge, a new strain.
- *Measure before tuning.* `Probe` prints the numbers for any outbreak; the tests check promises, not one seed's
  counts. Balance was tuned against probes on several scenarios.
- *Instrumentation from day one.* `Audit` and state hashes are part of the game, not a side tool.

## What's next (not in 1.0)

- Fire brigades and fires; paramedics.
- A strategic fast-forward that resolves fights in counts when nobody is watching anywhere (now: up to 16x).
- Railways and trains; ports and the navy; airports.
- Several regions joined into a country.
- GPU rendering (OpenGL ES) for more individuals on screen at once.
