# Zombie City Sandbox 2

The sequel to Zombie City Sandbox. The first game was one city and one outbreak, watched from above. This one is
a country: a capital of about a million people, three to five other cities, dozens of towns and villages, farms,
forests, rivers, a coast, interstates, railways and bridges, army bases, an air base, an airport, a naval
station and prisons, divided into states. About two million people live in it (a "one state" map has about one
million, a large country more). Start an outbreak anywhere and watch every part of the country respond, or take a
place in the response at any level, from the President down to one police officer, or try to live through it as
one person.

**Download:** [`release/ZombieCitySandbox2.apk`](release/ZombieCitySandbox2.apk) (Android 5.0 and newer, about
320 KB: every picture and sound is made by code). It installs alongside the first game.

## What's in it

- **One camera from a street corner to the whole country.** Pinch to zoom. Close in, every person, car and
  shot, and buildings that stand up and lean as you pan. Further out, people become dots. From high up you see
  the infection as a red stain over the map, hordes on the march, refugee columns on the roads, formations as
  unit symbols with arrows and front lines, state borders and the towns' populations.
- **Map layers** (the Map tool): infection, utilities (power, water and phones for every town), supplies
  (ammunition, food and fuel at each depot and station), public trust and fear, forces and fronts, where people
  died, and the peak of the infection.
- **Millions of people, simulated two ways.** Everyone indoors is counted by the sector (512 px squares). Where
  something is happening (the dead near the living, police and soldiers, or the camera), the counts come out as
  individuals; when it's quiet they fold back in. Every sector is simulated every second, watched or not.
- **Forces at every level.** Police wait at their stations as numbers and come out as squads when sent. The army
  moves as formations on the national map (infantry and armoured battalions, artillery batteries, marines,
  National Guard brigades) and holds ground; where nobody is watching, fights are settled by the numbers. Outside
  help arrives by the interstates as the alert level rises.
- **Time.** An hour passes every minute at 1x. Fast-forward at an hour, six hours or a day per second through
  weeks of a campaign. Seasons and weather: the cold slows the dead, the heat rots them, rain and fog shorten
  sight, snow lies on the ground.
- **The dead.** They chase what they see, follow the scent of the living round buildings, drift towards people
  and lights, break into buildings, and gather into hordes that march across the country. New strains appear as
  the days pass.
- **Play as anyone** (chosen on the New Game screen; the AI runs everything else):
  - *God*: every tool. Paint the dead, place forces, strike, order anyone, decide everything.
  - *President*: alert level (up and down), martial law and mobilisation (declared and lifted), curfews, quarantines, evacuations, air strikes,
    artillery, bridges, and every formation, or hand the army to the AI general ("Let the general command" in the
    Order tool) or release single formations to it (and take them back) from a formation's sheet.
  - *Military commander*: the formations, air strikes, artillery and bridges, with the same hand-over to the
    general; the civil government decides the rest.
  - *Incident commander*: one city's police and the soldiers sent there, its evacuation, and asking for the army (the army comes when you ask; on the coast your goal is your own city).
  - *Precinct commander*: one police station; send its squads out and move them. In a campaign your goal is your
    own district: the other stations leave its calls to you.
  - *Squad leader*: one squad; tell it where to go (in a campaign: keep it alive and its streets clear).
  - *Police officer*: one officer with a patrol following; move with the stick, you shoot by yourself.
  - *Survivor*: one person; move with the stick, survive.
- **Scenarios and campaigns.** Sandbox starts: an open country, a lab leak, a crashed airliner, a prison riot, a
  summer festival, a ship in the port, somewhere random. Campaigns: hold the capital for 30 days, evacuate the
  coast, retake a fallen state, protect the capital from hordes of tens of thousands. A campaign's progress (and
  the days left) is a line under the top bar and a section of the pause menu. Survival: as a survivor or on patrol
  as an officer.
- **Director mode.** Every game records what the player did and where the camera looked; save a replay from the
  pause menu (a few kilobytes) and it rebuilds the whole outbreak from its start, exactly, and you can take over
  when it ends. A timelapse plays the country back hour by hour.
- **Shared worlds.** A challenge code (map size, terrain, seed, scenario, role), shown on the New Game screen
  and in the pause menu, can be typed in to play the same game. Your best for each code (fewest lives lost,
  fastest containment) is kept and shown.
- **Modding.** Make your own scenarios in the editor (where it starts, how many, hordes, goal, days, season,
  role). Put a `content.ini` in the app's files and it replaces the built-in one.
- **Readable at every scale.** Everything on the map is sized for the phone's screen (people, vehicles, unit
  symbols, labels with outlines), crowds glow where they're thick, and labels don't pile on top of each other. Tap
  anyone or anything: names, ranks, ammunition, orders, a formation's strength
  and losses, a town's people, the dead and its power. The radio log keeps the whole story, thousands of messages, each said once (tap one to go there).
  The after-action report has a timeline, the turning point, the numbers, where people were lost (in their homes,
  on the roads, to hordes, to our own guns, in fires), the best fighters, what happened, the fallen, and your best
  for the challenge.

## Controls

Drag to move, pinch to zoom, tap to look. The tools are along the bottom (down the left side when the phone is
sideways); each says in a line above its options what a tap does. The **Map** tool switches the national map's
layers, and its first button jumps out to the whole country and back (on a tall phone the country fills the
screen's height and can be panned sideways); the minimap jumps too. Strikes are aimed,
then confirmed. Red arrows at the map's edge point to the biggest fights out of view. The **?** button explains the
marks on the map. Top right: pause and speed (1x to 16x, then fast-forward). The menu button (or the phone's back
button) pauses, and the back button also cancels a load in progress. The officer and the survivor can't
fast-forward (the speed button goes round 1x to 16x), but while none of the dead are near, Skip ahead runs time on
as fast as the phone can and stops by itself when they come close; when the phone can't keep up with a fast-forward speed, the
button shows the rate it really reaches ("~15h/s"). The game saves itself every three minutes of play and whenever
the app is paused. On a desktop: the mouse is a finger, the wheel zooms, space
pauses, 1-8 set the speed, M toggles the map, Esc is back.

## Building and testing

The game is plain Java with no libraries and no game engine.

```
./test.sh              # the test suite, on a desktop JVM
./test.sh --ui         # plus a scripted run through the interface, saving screenshots to build/uishots
./build.sh             # the signed APK, into release/ZombieCitySandbox2.apk
./play.sh              # the game in a desktop window
```

`build.sh` needs the Android command-line tools: on Ubuntu or Debian,
`sudo apt-get install aapt dalvik-exchange zipalign apksigner android-sdk-platform-23`.
The tests and `play.sh` only need Java 8 or newer. CI (`.github/workflows/build-apk.yml`) runs the tests and
builds the APK on every push.

Headless tools (run from the repository root, with `build/test` on the classpath): `Probe` (an outbreak of any
size, printing the numbers; `STRAT=days` then fast-forwards), `Shot` (the world at five zooms), `UiShot` (the
interface driven by scripted taps; `UiShot dir 1080 2400 2.75` shoots a phone in portrait, `UiShot dir 2400 1080
2.75` sideways, and `UiShot dir 1080 2400 2.75 director` walks the roles, campaigns, editor, layers, the hand-over
to the general, the timelapse and replays).

`tools/balance.sh 5000,50000,100000,250000 8 2304 30 4` plays each outbreak size on many seeds in fast-forward
and prints the spread of outcomes (days to contain or fall, share of the country lost, losses); every run goes to
`build/balance.csv` (a run counts as lost when a third of the country's people are gone). At the time of writing
(6 seeds each, the default map, 30 days): 5,000 dead are put down in about half a day; 50,000 became wars of 11
to 30 days (4 won, 1 lost, 1 still going after a month, about a fifth of the country bitten or killed); 250,000
were all won in about 20 days with about a quarter of the country lost. The bigger outbreak is put down more
reliably because the army's response scales with the dead that are reported; that is a known balance issue. The alert, curfews, shelter orders and evacuations then come down, outside units go
home and people return to their towns.

The game draws with Android's Canvas by default. Settings has "Renderer (next start)": choosing GPU (OpenGL ES
2.0, experimental) takes effect when the app next starts. On its first frame the GPU renderer draws a test pattern
and reads it back; if that fails, or GL reports an error in the first frames, it switches to Canvas for good and
says so. The GPU renderer has only been compiled, not yet run on a real phone.

If something goes wrong in a frame the game doesn't close: it saves (unless the world was in the middle of a step),
says what happened and offers the menu. A save that can't be read is renamed to `.bad` and no longer offered.
Replays copied into the app's files as `.zcr` appear in the replay list.

## Changing the game: content.ini

Every person, weapon, vehicle, zombie type, squad, formation, scenario and the simulation's balance rules are
defined in [`assets/content.ini`](assets/content.ini), not in code. Add a section to add a type. A scenario can
set its role (`role = officer`), its season (`season = 355`), and start anywhere (`origin = xy:0.4,0.6`).

## Layout

```
src/core/      the whole game, platform-free (simulation, rendering through Gfx, interface, audio synthesis)
src/android/   the Android shell: activity, view, Canvas drawing, AudioTrack, files, settings, text input
desktop/src/   the desktop shell, tests, probes and screenshot tools
assets/        content.ini
docs/          DESIGN.md (how it works and why) and the first game's design brief
```

See [`docs/DESIGN.md`](docs/DESIGN.md) for how the simulation works, and how it answers the lessons of the
first game.
