# Zombie City Sandbox 2: design

How the sequel works, and how it answers the lessons written down in the first game's brief
([original-game-brief.md](original-game-brief.md), sections 14 to 16).

## The shape of it

| | First game | Sequel |
|---|---|---|
| Map | One city, 424-752 tiles | A country, 1536-3072 tiles: a capital, cities, dozens of towns and villages, states, bases, rail, ports (768-1024 tile regions still build, for tests) |
| People | ~3,000 individuals | ~1-3 million residents counted by the sector (about 2 million on the default map), plus up to 60,000 (setting) individuals where it matters |
| Forces | Every officer an entity | Pools at stations and bases, formations on the national map; individuals only when deployed |
| Player | A sandbox god | Eight roles from God and the President down to one officer or survivor; replays, challenge codes, an editor |
| Crowds | Each zombie steers, pairwise separation | One scent field (breadth-first search) twice a second; the dead walk downhill on it |
| Threads | One | Parallel think and move phases, deterministic |
| Entity state | Booleans (dead, removed, listed), lazy sweeps | One location per slot, changed only by four methods |
| Behaviour versions | `if (civic >= N)` everywhere | None: saves are versioned, the rules aren't |
| Content | Code (40,000 lines of landmark drawing) | `assets/content.ini` for every type and scenario |
| Tests | 25, about 12 minutes, Android stubs | About fifty, a minute or two, no stubs (the core has no Android code) |

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
| Player | `Tools`, `Setup`, `Actions`, `Roles` | Everything the player can do (every change goes through `Actions`, which records it); building a new game, its scenario and the player's role; the country-scale campaign goals |
| Director | `Director`, `Replay`, `DirectorScreens` | The record of inputs, replays from a game's start, the timelapse, heatmaps; the replay list, timelapse and scenario editor screens |
| Checking | `Audit`, `SaveIO` | Invariants and state hashes; saves |
| Presentation | `Gfx`, `GImage`, `Platform`, `Camera`, `MapPainter`, `WorldView`, `Ui`, `Icons`, `Game`, `Settings`, `Texts` | Drawing at every zoom, the interface, the screens |
| Sound | `Synth`, `Mixer`, `AudioDirector` | Every effect, bed and score stem, synthesised once; mixing with priorities and a limiter; what to play, where, and the captions, vibration and shake that go with it |
| Android | `MainActivity`, `GameView`, `GLGameView`, `AndroidGfx`, `GLGfx`, `AndroidImage`, `AndroidPlatform` | The shell: Canvas by default, the GPU renderer if chosen |
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

## Drawing for a phone

Everything that marks something on the map is sized in dp (`Camera.dp`), not world pixels, so a person, a car, a
unit symbol or a label reads the same on any screen at any zoom; labels have outlines (`Gfx.textHalo`) and are
placed most important first, skipping any that would land on another, on a formation's symbol, outside the map
area the bars leave free, or under the interface's own slots (the radio, the note above it, the minimap, the key).
Notes and the radio are stacked up from the bottom of the map in separate slots, so they never cover each other.
The ground is painted into 32-tile chunks at four levels of detail (4, 9, 16 and 28 pixels a tile) within a time
budget each frame and a memory budget overall.
Soft things are small pictures scaled up with smoothing: glow sprites (street lights, fire, sirens, smoke), the
infection's bleed and the heatmaps (two pixels a sector, blurred), the glow of crowds (the 64 px grid round the
view). The interface's bars cover the screen's edges, and the camera knows it (`Camera.setInsets`), so "the whole
country" fits the map area between them; on a tall screen it fits the map's height instead when that puts more
land on the screen (a coarse land mask scores each zoom), and the rest is a pan away. Sideways, the tools become a
rail and the inspector a side sheet. A campaign's progress (`Roles.progress`, `progressText`) is a line under the
top bar.

## Saves version the format, not the rules

`SaveIO` writes everything the simulation depends on, including the scent field, noises and the dice, so a
loaded game continues exactly as the original would have (the test compares state hashes for 40 seconds after
loading). The region is rebuilt from its seed and the saved tiles (rubble, blown bridges) laid over it. There is
one save version and no behaviour gating. A save made with different rules (`content.ini` hash) loads with a note.

## Playing at any level

`Roles` (a module, saved) holds who the player is and their piece of the world: a city for the incident
commander, a police station for the precinct commander, a squad for the squad leader, an entity for the officer
and the survivor. A role decides the tools the interface shows, which squads can be ordered (`canOrder`), whether
the national decisions (`Gov.playerDecides`: the alert, martial law, mobilisation, the curfew) and the military
command (`Forces.command`) step aside, and which station the dispatcher leaves to the player. Mayors, police chiefs
and governors keep deciding for their own towns and states whoever is President; an incident commander's city
gets its evacuation and the army only when the player asks. Everything else stays with the AI. In a campaign the
small roles play for goals of their own (`Roles.goal`): a precinct's district kept alive, a squad kept alive with
its streets clear, an officer or survivor alive for two days. The officer is an armed police entity the
stick moves (`ArmedAI` hands it to `Combat.playerThink`), with its patrol kept close each second. A person can't
be fast-forwarded (that would fold them into the counts); instead, while none of the dead are near, "Skip ahead"
steps the world in person as fast as the device can and stops when the dead come close. Speed is not an input, so
replays don't change. A new game opens on the player's own piece (`Roles.focusOf`), with a button to the outbreak.

## Director mode: replays are exact

Every change the player makes goes through `Actions`, which records it in `Director` with the tick it happens
before, and then carries it out through the same `apply` a replay uses. The camera is an input too (it decides
which sectors are simulated in detail), so `Director` records it as the simulation saw it at each once-a-second
update; so are the stick, fast-forward and the people-in-detail setting. A replay is the game's start (map,
seed, scenario as text, role) plus those events: `Replay` rebuilds the world and feeds the events back before
each step, and the test `director: a replay rebuilds the same outbreak` checks the state hash matches after a
session with painting, orders, strikes, panning and fast-forward. The timelapse stores the dead and the people of
every sector once a game hour as log-scale bytes (about 10 KB an hour on the default map, inside the save).

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

## The whole society

Systems added as modules, each saved with the game and run in fast-forward too:

- `Infrastructure`, `Logistics`: water, phones, food, medicine and fuel that can fail; convoys of goods by truck,
  freight train, cargo plane and ship, which the dead can destroy and blown bridges reroute; evacuation trains.
- `Command`, `Media`, `Society`: mayors, police chiefs, governors, the president and the general deciding from a
  reported picture that lags and under-counts (worse with the phones down); approval and replacement; news,
  online panic and rumours that change behaviour; the hidden bitten, families crossing town, holdouts,
  militias, looting, refugee camps, survivor settlements.
- `Ecology`, `Fires`, `Navy`, `Engineers`: hunger, nests, local strains that spread, hordes that follow roads and
  valleys and split; fires that spread downwind and fire brigades; destroyers, the coast guard, jets from the air
  base, airlifts; forward bases with field hospitals, and barricades across cordon streets.
- `Roles`, `Director`: the eight roles; recorded actions, exact replays, the timelapse and heatmaps.

They interact: hunger brings looting, looters and dry towns start fires, fire crews without mains water are weaker,
phones down slow the calls and the officials' picture, rumours move people onto the roads where the dead are.

## The shape of an outbreak over weeks

- *Pacing.* Indoors, only so many of the dead can reach the people sheltering at once, and a street (sector) loses
  at most `maxBitePerHour` of its people an hour however many are outside; the bitten turn over hours
  (`turnTime`), not minutes. More than `sectorSpill` dead in one sector spill into the next streets. Residents put
  down a few of the dead where they far outnumber them. Fights as numbers (`aggregateKill`, `aggregateLoss`) cost
  ammunition (`roundsPerKill`), and formations draw it from depots close behind them and from convoys (escorted,
  so harder to stop than a town's food lorries).
- *Imperfect information, both ways.* `Command` believes a share of the truth, inflated a little by panic online,
  catching up with a lag; officials take `reportClearLag` times longer to believe the dead are gone. In
  fast-forward nobody is taking 911 calls, so stale calls no longer hold the picture up.
- *Coming down.* The president lowers the alert a step at a time once the picture has stayed well below the mark
  for `lowerAfter`; curfews, shelter orders, evacuations and quarantines are lifted after quiet spells; outside
  units go home over the border (`Recovery`); a town clear for two hours is declared safe with what it cost; and
  people come home from wherever they sheltered (`Strategic.homecoming`), counted once in `Strategic.refuge`.
  Once the country is out of danger nobody more leaves it, columns on their way out turn round, and those who went
  abroad come back (`Strategic.fromAbroad`). Everyone is accounted for: alive (at home, on the road, abroad) plus
  killed plus turned is the starting population, always (`Audit.ledger`, checked by a test). Patrols hunt the last
  few dozen dead in quiet streets and fields once the war is all but won (`Recovery.sweep`).
- *The general.* Every half minute `Forces.command` makes a plan: towns by the reported dead, hordes and nests in
  open country by reconnaissance, formations in proportion to each, each to its own part of the line
  (`frontFor`); free formations join the biggest fights; idle ones go home. The player as President or Military
  commander commands the army until they hand it over (`Actions.generalCommands`, "Let the general command" in the
  Order tool) or release single formations (`Actions.releaseFormation`); ordering a formation takes it back.
- *Evacuation.* The roads out of a town carry `evacPerHourTown`/`evacPerHourCity` an hour; a destination takes
  refugees up to `hostShare` of its people; the bitten among each town's leavers are counted per town.
- *The news.* A line is said once (its key is the text without its counts, so a changing number doesn't make it
  new); things that happen again and again are said the first time and then added up in one line every few hours
  (`News.tally`); every number goes through `News.num` (1,234). A town asks for the army once at each step its
  outbreak gets worse, and nothing is ordered before it was reported. Rumours start where people are frightened, one town at a time.
- *Campaign goals* are judged from the first full count, over time (`Roles.goals`): the capital's own people alive
  at home or safe elsewhere; the coast's people safe inland; the dead in the fallen state down to one in a
  hundred for two hours; the capital lost only if the dead hold a fifth of its streets for a whole day.

## Balance over many runs

`desktop/src/.../Balance.java` (and `tools/balance.sh`) plays outbreak sizes over many seeds in fast-forward and
reports percentiles; a test runs a small batch on every `./test.sh` and checks broad bands. The rules it tunes are
in `content.ini [rules]`.

## Playing together

Pass-and-play: up to four players share one game, each in a role (Roles.players, pRole, pTarget); the AI stands
aside wherever any player holds the job (Roles.holds). Adding a player and handing over are recorded actions, so
a replay of a shared game is still exact. Results on a challenge are shared as a checked code
(`ZC2R~challenge~name~lost~minutes~finished~check`) and compared on the report.

## Threads on the phone

The game lives on one thread: the UI thread with Canvas, GLSurfaceView's thread with the GPU renderer. Touches
and the back button are handed to it (`queueEvent` for GL). Text typed into a dialog arrives on the UI thread, so
`Game.ask` hands each answer to the game's thread with `Game.post`, carried out at the start of the next frame.
New games, loads and replays build in the background; each load has a number, so a load cancelled with the back
button (or replaced by another) is thrown away and its world shut down when it finishes. The first finger down
(and a cancel) resets the game's idea of which fingers are down, so a lost UP can't leave every later tap
counted as a second finger. An exception in a frame is caught: the game saves (unless it happened mid-step, when
the world may be half-changed), shows what happened and offers the menu. It also saves every three minutes of
play. The sound runs on its own thread, one at a time (a quick pause and resume can't leave two mixing).

Sound never touches the simulation. The simulation only leaves sound events in `Effects` (a name and a place);
`AudioDirector` reads them, and the world, on the game's thread after the frame's steps, with its own dice. A
sound's loudness and pan come from its distance to a listener above the camera's centre (higher the further out
the camera is, so a zoomed-out map is quieter and farther guns are their muffled "far" versions). Each frame
plays a handful at most, the important and loud first, merging repeats; the mixer caps copies of one sound, lets
a sound take only a voice of no higher priority, fades out any voice it takes, and limits the sum. Loops (sirens,
engines and rotors from the nearest vehicles of each kind, with Doppler; the town's hum, wind, birds or crickets,
rain, storms, fire, a panicking crowd, a horde's rumble, a fight off-screen) glide to the levels the director
sets every quarter second. The score is seven stems in one key and tempo (24 s loops); a cue (calm, tension,
battle, aftermath, strategic) is a mix of them, so changing cue is a cross-fade that stays in time. Everything is
rendered once at start on a background thread (about 10 MB); until a buffer is ready it is simply skipped. On
the desktop `AudioDump` writes it all, and a captured game, to WAV files.

The simulation steps inside the frame within a 28 ms budget; when a fast-forward speed is more than the phone can
do, the rest is dropped rather than queued, and the speed button shows the rate actually reached.

## What's next

- Running the GPU renderer on real phones (it has a self-check and a Canvas fallback, but hasn't met a device).
- Online play: co-op is pass-and-play on one phone and results are compared by code; playing together over the
  internet would need a server.
- Several countries; the sea as a place.
