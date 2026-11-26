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
| Content | `Content`, `Data`, `assets/content.ini` | Weapons, units, zombie types, vehicles, scenarios; names, buildings, presets, origins, outside help |
| The region | `RegionGen`, `Region`, `RoadGraph`, `Tile`, `Noise`, `Names` | Terrain, rivers, settlements, street grids, blocks and lots, facilities, the army base and prison, highways (A* over the terrain, smoothed, bridged), farms; the road graph and its routing |
| The world | `World`, `Ents`, `Spatial`, `Flow`, `NavPath`, `Parallel`, `Rng` | Entity storage, the spatial grid, the scent field, foot paths, the worker pool, deterministic randomness, and the order of a step |
| Two tiers | `Sectors` | The counted population and dead, indoor infection, coming out onto the streets and going back in, migration |
| Behaviour | `ZombieAI`, `Undead`, `CivAI`, `ArmedAI`, `Combat` | Thinking (in parallel) and carrying it out (in order): bites, shots, blasts, deaths, rising; the dead smashing, feeding, leading, lunging |
| Forces | `Squads`, `Vehicles`, `Dispatch`, `Military` | Squads and their orders; driving on the road graph and flying; 911 calls, cordons; the army, gunships, tanks, air strikes, supply, outside help |
| The region-wide | `Strategic`, `Gov`, `News`, `Stats`, `Records` | Facilities, power grids, ammunition, hordes, refugees, bridges, strains, goals; the alert level, trust, curfews, evacuations, rumours; the radio log; the numbers and the report; why things happened (a tracer of each fighter's last seconds, why towns fell, each formation's story, decisive moments, callouts and the alerts fast-forward stops for: read-only, a module of its own) |
| Player | `Tools`, `Setup`, `Actions`, `Roles`, `Defences`, `Orders`, `Rules`, `Survival` | Everything the player can do (every change goes through `Actions`, which records it); building a new game, its scenario and the player's role; the country-scale campaign goals; what the player builds, lasting orders, driving and possession, and the rules sheet; living through it as one person (hiding, scavenging, needs, weapons, melee, cars, companions) |
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

## A normal day (Life and Commute)

The counts move with the clock (rule `lifeFlow`): every sector has a weight as a place of work (offices, shops,
schools, industry, the centre; from the buildings on today's maps, from the district types on national ones), and
once a game hour, in a phase fixed by its id, each town's calm sectors (nothing of the outbreak in them) are brought
to the hour's spread: up to `lifeFlowShare` of its people at the places of work by day, everyone at home by night.
A sleeping district's people change without waking it (`SectorIndex.addPop` keeps its frozen totals), so a quiet
country stays cheap, and the frozen totals check (`SectorIndex.paranoid`) still holds.

Round the camera at street level (rule `life`) the city goes about its day as a level of detail, only inside the
camera's rectangle and a margin, so only in active chunks (the city's vehicles never pull the active set along).
Every car's driver and every bus passenger is a resident of a sector who gets in (from `popIn`, or `inf` for the
bitten) and is counted again where they get out, or where they were going when the camera leaves; a train's
passengers are counted aboard (`Life.carried`, in `Audit.people`). Cars (`Vehicles.R_LIFE`) are ordinary vehicles:
they drive, queue, run the dead down, are wrecked and burn, and are saved with the rest. How many depends on the hour
(`Life.rush`) and the streets (the centre busy all day, the suburbs at the rush hours); parked cars fill the kerbs'
places that a hash of the street keeps, and their alarms go off when the dead brush past (a sound, and a noise on
the scent field). When the dead come, cars flee away from them, jam, and are abandoned with their people running.
Buses loop their town's lines stop to stop, people getting off and on; trains come through on the railways and stop
at stations, carrying the bitten with the rest. Each police station in view sends patrol cars round its district,
and each base a humvee round its fence, from their pools, standing down into them when out of sight; the people
still in the pools stand in the yards and bases as figures (sentries, a fence patrol, a drill by day). People come
out of doors with somewhere to be for the hour (work or school in the morning, the shops by day, home in the
evening), walk there and go in; some come out together and follow whoever leads them, running with them when the
dead come, and a crowd packed tight in flight tramples some underfoot. Everything is decided on the game's thread
with hashed dice, except following a leader, which reads only where the leader is.

## The dead as a fluid

`Flow` runs one multi-source breadth-first search over the 32 px navigation grid from every living person on the
map (distance 0) and every noise (gunfire, blasts, screams; distance 56 minus loudness), out to 56 cells. A
zombie with nothing in sight walks to its lowest neighbouring cell. Thousands of the dead find their way round
buildings to a fight for the cost of one search, instead of a path search or a steering check each. Nearby
targets are chased directly; separation looks at a sample of the same cell only. 20,000 individual zombies in
the city with its police run at about 2.5 ms per step on the 4-core desktop the tests ran on (the first game's
brief measured 15-19 ms for 20,000 on its own machine; not the same machine or the same fight, but the same scale).

## The dead up close

`Undead` (a module) holds what the dead do beyond chasing and biting. Each zombie's small state lives in its own
`Ents.state` byte (set up, strain, attack phase, feeding, led, marching; spawning and rising clear it), so most of
the dead read nothing new; the rest is kept by slot and read only when the byte says it means something. All of it
follows the think-phase rule: each zombie writes only its own slot, and reads others only through state the think
phase never writes (position, velocity, uid, type). Leaders are a hash of the uid, so anyone can ask.

- *Barricades, fences and walls* have hit points (`[rules zombies]`) in a sparse damage map (saved). A zombie
  pressing towards someone (or along the scent) into one beats on it (`smash` per blow: a walker 1, a brute 12, a
  giant 45); a broken tile becomes rubble and, at the end of the step, the navigation grid, the sectors' connectivity
  and the roads closed by barricades catch up. Dogs (`breakIn = 0`) can't.
- *Feeding*: whoever bites someone to death, and a few idle dead round them, stop at the body; each feeder slows its
  rising, and enough feeding (`feedDevour`) leaves nothing to rise.
- *Frenzy*: close on a victim with others pressed round, they move faster and bite again sooner.
- *Alphas* (`leaderShare`, a pack leader for dogs) roar when they have someone or are on a fresh scent: a noise on
  the scent field. The dead round about keep with them: on the scent they slow when ahead of their leader and hurry
  when behind; without one they follow its way.
- *Big ones*: brutes and giants charge (knocking the living back) and shove cars aside, denting them until they're
  wrecks (a shoved car stops against the next vehicle; a wreck stays put, an obstacle the rest park round);
  bruisers wind up, lunge, then rest.
- *Strains* change the dead themselves: runners' strain is faster, the armoured tougher, stalkers see further and
  move faster at night.
- *A watched horde* (`Strategic` asks `Undead.watchedHorde`) comes on with its front (`hordeFront`) as individuals in
  a band walking on along its heading; the rest is the mass behind them in the sector's count.

## One owner for every entity

`Ents.loc` is one of NONE (free slot), FREE (on the map), ABOARD (in a vehicle), BODY, or INSIDE (the survivor and
their companions hiding in a building, see below). Only `spawn`, `board`, `alight`, `goInside`, `comeOut`, `die`,
`rise` and `remove` change it, and each checks the state it expects and throws if wrong. A
vehicle's crew list and its crew's `loc` are changed in the same call. `Audit.check` verifies every invariant
(crews agree, squads agree, nothing off the map or NaN, the live count matches) and runs inside the tests every
five seconds of every outbreak. The first game's "people listed twice" and "police lost off the map" can't happen
silently here: they would fail the audit.

## Police and soldiers on foot

The squad says where; each officer or soldier (`ArmedAI`) decides how. About twice a second each one scores its
options and takes the best: engage; move to cover or a better line of fire; fall back to the squad's fallback point
(its vehicle, or behind its line) when the dead outnumber what the guns near can hold; pull back to a medic when
wounded, while the others stand over them; reload in cover (weapons have magazines); search where the dead were
last seen; rejoin the squad; or hold as the rear guard for a moment while the squad falls back. Whoever is too close
backs away from all the dead round them, not just the nearest. Roles add their own job: a medic treats the wounded
and holds off the turning of the bitten for a minute; a commander steadies and sharpens everyone near; a marksman
takes a rooftop for overwatch (better aim and reach); a K9 handler's dog finds the hidden bitten among people near,
who are reported and taken off the street; a traffic officer holds the junction by the squad's post; a drone over the
squad shows the authorities what it sees (`Command` believes nearly all of it, sooner) and shows the squad where to
search.

Nerve is per person: their training, rank (from kills) and role, a commander or the command post near, the losses
their squad has taken, the odds, their wounds and their ammunition. When it runs out they break and run for the
fallback point, and rally once clear and steady; the squad holds longer when its people are steady
(`squadNerve`). Doctrine is per agency: police hit harder up close (stopping power stops a bite, aimed headshots)
and seek cover; the army suppresses packs with sustained fire and bounds forward, half moving while half covers;
SWAT clear buildings where the dead are inside faster than anyone. All of it is decided in the parallel think (each
writes only its own state) and what touches others (treatment, the dog, the drone, breaching) once a second on one
thread; the state is saved with the game.

## Vehicles on a graph

Roads are a graph built during generation (junctions and the streets, avenues and highways between them, with
bridge flags). Vehicles route with A* on it (with per-edge penalties) and drive by steering at a point a little
ahead in their lane, queueing behind whoever is in front. No vehicle moves into another: one that would waits (at a
corner where two are nose to nose the newer one backs off a little), one stuck for 3.5 seconds swings out towards
the middle of the road to get past, and after ten seconds with no way through it stops where it is. Arriving, each
pulls in to a place of its own at the kerb near the end of its route (half on the pavement, facing the way its
side of the road runs, clear of the junction and of every other vehicle and place being pulled in to); one put down
where another stands, or going home to a taken spot, gets the nearest free place instead. They run down
the dead at speed and get mobbed when they stop among them. Blowing a bridge removes its edges: routes go round,
and the river becomes a line hordes can't cross (sector connectivity is recomputed).

## Drawing for a phone

Everything that marks something on the map is sized in dp (`Camera.dp`), not world pixels, so a person, a car, a
unit symbol or a label reads the same on any screen at any zoom; labels have outlines (`Gfx.textHalo`) and are
placed most important first, skipping any that would land on another, on a formation's symbol, outside the map
area the bars leave free, or under the interface's own slots (the radio, the note above it, the minimap, the key).
Notes and the radio are stacked up from the bottom of the map in separate slots, so they never cover each other.
The ground is painted into 32-tile chunks at four levels of detail (4, 9, 16 and 28 pixels a tile) within a time
budget each frame and a memory budget overall. A chunk is painted as pixels: the ground (the smooth edges worked
out only for tiles with another kind of ground within four tiles, the spline separated into rows), then the marks
on it and the trees, anti-aliased by coverage; only the lane markings are drawn on the canvas. A picture the cache
lets go of is painted over for the next chunk rather than made anew.
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
loading). The region is rebuilt from its seed and the saved tiles (rubble, blown bridges) laid over it. The format
has a version and the rules have none. A save from an older version (back to 3, the game's 1.2) loads with the
fields added since at their defaults: SaveIO rewrites an older module's bytes into today's layout before the module
reads them, and works out what it can (the population's ledger, who commands the army); one older still is offered
for deleting. A save made with different rules (`content.ini` hash) loads with a note.

A save is a snapshot (the world written out uncompressed, about 15 ms on the desktop for the largest map) and then
its compression; the autosave takes the snapshot on the game's thread and compresses and writes it on another, so
it doesn't hitch, and the file is the same, byte for byte, as a save made at once.

## Playing at any level

`Roles` (a module, saved) holds who the player is and their piece of the world: a city for the incident
commander, a police station for the precinct commander, a squad for the squad leader, an entity for the officer
and the survivor. A role decides the tools the interface shows, which squads can be ordered (`canOrder`), whether
the national decisions (`Gov.playerDecides`: the alert, martial law, mobilisation, the curfew) and the military
command (`Forces.command`) step aside, and which station the dispatcher leaves to the player. Mayors, police chiefs
and governors keep deciding for their own towns and states whoever is President; an incident commander's city
gets its evacuation and the army only when the player asks. Everything else stays with the AI. In a campaign the
small roles play for goals of their own (`Roles.goal`), each needing the player to act: a precinct's district kept
alive at home or evacuated, and its station held (its levers: answering the district's calls, a police cordon on
the district's edge that turns the counted dead back, and evacuating the district door to door); a squad that puts
down sixty of the dead and stays at least half alive; an officer who reaches the scene of four 911 calls and lives;
a survivor who reaches safety (the army base, an evacuation point, a survivor settlement or the coast guard's boats,
`Strategic.safePoints`). The officer is an armed police entity the
stick moves (`ArmedAI` hands it to `Combat.playerThink`), with its patrol kept close each second. A person can't
be fast-forwarded (that would fold them into the counts); instead, while none of the dead are near, "Skip ahead"
steps the world in person as fast as the device can and stops when the dead come close. Speed is not an input, so
replays don't change. A new game opens on the player's own piece (`Roles.focusOf`), with a button to the outbreak.

## Living through it as one person

`Survival` is a module only in games with someone to play (the survivor, and the officer's own play): `Setup`
calls `World.enableSurvival`, and a save that carries its part brings it back (`SaveIO.readModules`), so every
other game, its saves and the golden hashes are exactly as before. Every verb is one `Actions.SURV` record
(`i[0]` the verb, then its arguments), carried out on the game's thread; its randomness is hashes of the seed, the
clock and the thing concerned. The player's think (`Combat.playerThink` hands it to `Survival.think`) and the
companions' (`CivAI` hands anyone flagged `F_COMPANION` to `Survival.companionThink`) write only their own slots.

- **Hiding.** Go in at a door within reach: the person goes `INSIDE` (off the street, out of the spatial grid, so
  the dead neither see nor smell them) and is counted in the door's sector like everyone indoors (`popIn`, so the
  population's ledger holds); `ActiveSet` keeps their chunk. Leaving takes them out of the count again (a count
  bitten while they hid lets them out bitten). Barricading turns the door's tile into a `BARRICADE` (tools add hit
  points as negative damage in `Undead`'s map), so the dead at the door have to smash it with their own
  `smashPower`; each second each of the dead within 30 px notices someone inside with a chance (more after a
  search or a shot). Without a barricade they get in (`Sectors.breakIn`) and every one inside raises the chance
  they find you: a bite, perhaps the infection, and you are thrown out into the street. The sector's counted dead
  add a small risk too, and a building may hold the dead already when you go in. Leaving takes our barricade down.
- **Scavenging.** Each building can be searched once (`searched`, by building id); a table by kind (homes, shops,
  police stations, hospitals, filling stations, warehouses, barracks) gives food, water, medkits, tools, fuel cans,
  rounds for each gun and the chance of a bat, a crowbar, a shotgun or a rifle, scaled by the building's size and
  by how often its sector has been searched (`yieldIn`: each search leaves the street poorer). The bag holds 12,
  and 8 more for each companion told to carry.
- **Needs.** Stamina goes with sprinting (the stick pushed past 0.6) and comes back walking or resting; food lasts
  four game days, water two (quicker in the heat), rest two (resting indoors restores it in five hours). Empty, they
  wear health away; full, small hurts heal; tired and hungry, blows and aim are less sure. The officer has none.
- **Fighting.** Weapons carried (`owned`), the gun in hand (its rounds are `Ents.ammo`, the others' kept apart) and
  the melee weapon; `Combat.shoot` takes the player's weapon from `Survival.gunWeapon`. A shove pushes everything
  within reach back and off balance (no damage, the big ones don't budge); a bat, crowbar or baton hits the
  nearest, killing with a chance or wearing it down. Manual aim (the Shoot switch's Tap aim) stops the automatic
  fire: a tap picks a target and Fire asks for one shot, which goes as soon as the gun is ready. Take gun takes a
  fallen officer's or soldier's weapon and rounds.
- **Cars.** An empty car within reach (one of Life's parked or abandoned ones, which then leaves Life as
  `R_TRANSPORT` with what's in its tank, or a squad-less one) is boarded and driven with `Orders.drive`/`wheel`;
  fuel burns with distance (40 km a tank) and an empty tank stops it. The dead claw at a slow car and drag you out
  (the existing clawing); then the wheel is let go.
- **Companions.** Up to three people near you, with orders follow, stay or carry; they keep to your trail round
  corners, step away from the dead, come into buildings and cars with you, are never folded into the counts, and
  can be bitten and turn (they leave the list).
- **The way out.** The nearest of `Strategic.safePoints` each second, for the card. The goals judge the person
  alive on foot, in a car or hidden (`World.playerAlive`, `playerX/Y`); reaching a survivor settlement joins it, and
  what you carry goes into its stores.

`SurvivalBot` (desktop) plays a survivor through Actions alone: it eats and drinks, searches, fights off what comes
close, hides and barricades when too many come, takes a car and drives the roads to the nearest safe place. On the
default map (2304, the survivor scenario, two days, seeds 1-6) it reached safety 6 times in 6, in 23-117 game
seconds (always by car); standing still, the idle survivor reached safety 0 times in 6 (alive at the end in all
six: the survivor starts well away from the outbreak). Walking only (`BOTNOCAR=1`), it still reached safety 6 of 6,
in 135-1,339 game seconds. On the small test map (768, one day, seeds 1-8): 8 of 8 against 0 of 8. The scenario is
forgiving at the start (the army base is a kilometre or two off); the needs, the dead inside and the mobbed cars bite
over days.

## The player's tools

Every verb is an `Actions` kind, so it is recorded and a replay carries it out at the same tick. The palettes are
built from `content.ini` (every zombie type, unit, vehicle and squad template, a mod's own too); a squad template
is placed with its vehicle. Three modules (saved) hold what lasts:

- `Defences`: sandbags (cover: `ArmedAI.cover` and `aimMul`, and bites land softer), razor wire (`ZombieAI`
  slows the dead in it; once a second it cuts them and wears out), machine-gun nests (a gunner pinned in place and
  a gun firing along an arc, on the main thread after the move), ammunition crates and medical kits, and command
  posts (the same nerve as a station or HQ near). Barricades, walls, roads, houses and clearing are tiles
  (`Tools.build`, `Region.set` into the overlay), with the paths, the sectors' connectivity and the roads closed
  by barricades caught up at once, as the engineers' walls are.
- `Orders`: a squad's lasting order (a route of waypoints, falling back with its fire held, a cordon line it spreads
  along, clearing an area, escorting a person or a vehicle), its stance (`Squads.Squad.stance`; hold fire is read
  in the think) and whether it stays mounted; vehicles sent to go, orbit, attack an area or return; the vehicle the
  stick drives (`Vehicles.update` hands it to `Orders.wheel`); the person God possesses (`World.playerEnt`, so
  `Combat.playerThink` moves them); and formations' postures (attack, defend, withdraw, screen, dig in), which
  scale the fights by the numbers in `Forces.aggregate`. Each order is drawn as a line and flags.
- `Rules`: the dead's speed and infection, force strength, civilians on the streets, how early people flee,
  outside help, the weather and the time of day, each read by one hook where it matters.

The dead brush goes to 250,000: up to 1,500 as individuals (`Tools.MASS_INDIVIDUALS`), the rest into the counts
of the sectors round about. The tests replay a session using every new verb, save and load it, and check that a
machine-gun nest kills and razor wire holds the dead back.

## Director mode: replays are exact

Every change the player makes goes through `Actions`, which records it in `Director` with the tick it happens
before, and then carries it out through the same `apply` a replay uses. The camera is an input too (it decides
which sectors are simulated in detail), so `Director` records it as the simulation saw it at each once-a-second
update; so are the stick, fast-forward and the people-in-detail setting. A replay is the game's start (map,
seed, scenario as text, role) plus those events: `Replay` rebuilds the world and feeds the events back before
each step, and the test `director: a replay rebuilds the same outbreak` checks the state hash matches after a
session with painting, orders, strikes, panning and fast-forward. The timelapse stores the dead and the people of
every sector once a game hour as log-scale bytes (about 10 KB an hour on the default map, inside the save).

**Seeking.** While a replay plays, `Replay` keeps snapshots: a save's bytes (`SaveIO.snapshot`, compressed), the
tick and the next input, at the start and every 30 game minutes, up to 48 MB; past the cap every other one is
dropped (the start stays) and the interval doubles. Going to a tick behind the world, or more than a game hour
behind a snapshot ahead of it, loads the nearest snapshot at or before it (on another thread: `SaveIO.load`
rebuilds the map from its seed, then the state) and plays on to the tick as fast as the device can; ahead, it
just plays on. A loaded save plays on exactly as the game did, so a seek lands on the same state as playing
straight through: the test `director: seeking to any time...` checks the hash at six checkpoints sought in a
scrambled order, with the snapshots squeezed until they thin out. The replay bar (`DirectorScreens.replayBar`)
shows the track with the snapshots and the camera's keyframes, and the clock; Step goes a game minute.

**Camera paths.** A keyframe is (tick, centre, zoom); `Replay.CamPath.at(tick)` is a Catmull-Rom curve through
them, the zoom on a log scale, eased at each key, holding before the first and after the last. The view follows
it (counting the step under way, so it doesn't stutter) until the player moves the camera. The camera in a replay
is not an input (the recorded camera events are), so a path changes nothing. A director's cut is the replay file
again (version 2) with the path after the inputs; version 1 files still load.

**Scripts.** A scenario can carry triggers and goals of its own (`Script`): a module added only to games whose
scenario has them (`Script.attach`, from `Setup.newGame` and `SaveIO.load`, before `Records`, which stays last),
so every other game and its save are unchanged. Once a game second each trigger not yet fired checks its
condition (time since the start, the dead in a town over N from `Stats.zBySettlement`, a town fallen by `Records`'
measure, a facility offline) and carries its action out through `Actions.scripted`: the same `apply` an input
uses, but not recorded, because a replay rebuilds the scenario from the header and the script fires again at
the same step. Goals end the game: survive N days or kill all the dead to win; a town losing more than (100 - N)%
of its people (`Stats.lostBySettlement`, which commuting doesn't move) or losses (civilians bitten or killed and
forces lost) over N to lose. The editor's other additions (outbreak points, squads and defences, rules, strain)
are carried out once by `Setup.extras` after the scenario's start.

**Data in content.ini.** `Data` holds the sections that were code: `[names]`, `[building]` (names, today's and
national roof palettes, pitched or flat, lit at night), `[preset]` (terrain kind and its numbers, rivers, a
naval station), `[origin]` and `[outside]`. Maps are made from size, preset and seed with no Content, so each kind
a loaded file has is installed for `RegionGen`, `ChunkGen`, `NationGen`, `NationPainter`, `Names` and `WorldView`
(a file without it keeps the one in use; a desktop tool with no content loaded reads `assets/content.ini`). The
built-in values are the old literals, parsed to the same floats and picked with the same dice (a one-colour roof
never rolls), so maps are byte-identical: the test `data: content.ini holds exactly...` compares region and
nation checksums, roofs and names recorded on the code before. These sections are left out of
`Content.rulesHash`, so saves and the golden test are unchanged. A content.ini is read with the line and section
of any mistake; an import (`Platform.pickFile`: Android's `ACTION_GET_CONTENT`, a file chooser on a desktop) is
checked without being put in use, then kept as the app's `content.ini`; Share sends the one in use
(`ACTION_SEND`).

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
- *Officials and the alert.* Each mayor, police chief and governor has a temper that comes with the person
  (`Command.trait`): cautious ones shelter, quarantine, evacuate and ask for help early, reckless ones late, so a
  replacement changes what a town does (and says so). A chief whose force is spent abandons the town: its squads go
  home, the police answer none of its calls, and the general takes it on first after the capital. Martial law
  enforces the curfew (people stay in, the streets empty), all but stops looting, rations the towns' fuel, and costs
  trust for as long as it lasts.
- *Size and response.* Mobilisation calls for the same mix of formations (Guard, infantry, armour, artillery) for
  any outbreak, sized to the reported dead up to `maxFormations`; so a small war is answered in kind and a huge one
  outgrows what the country can field. The dead are harder to find as they thin out (`findHalf`), so the last of
  them take the longest. Streets the dead have overrun (`overrunRatio` of them to each person left) go hungry, and
  the hungry set off as hordes for where people still are; hordes fall on refugee columns on the roads unless
  soldiers are near to escort them (`escortReach`).
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
- Exporting a stretch of a replay as pictures (PNG frames, or a GIF): Android can write PNGs without a library
  (`Bitmap.compress`), so it is feasible, but it is not built yet.
