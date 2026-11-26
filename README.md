# Zombie City Sandbox 2

The sequel to Zombie City Sandbox. The first game was one city and one outbreak, watched from above. This one is
a whole region: a city, towns, villages, farms, forests, rivers, a coast, highways and bridges, an army base and
a county prison. Hundreds of thousands of people live in it. Start an outbreak anywhere and watch every part of
the region respond, or take command of the response, or try to live through it as one person.

**Download:** [`release/ZombieCitySandbox2.apk`](release/ZombieCitySandbox2.apk) (Android 5.0 and newer, about
200 KB: every picture and sound is made by code). It installs alongside the first game.

## What's in it

- **One camera from a street corner to the whole region.** Pinch to zoom. Close in, every person, car and shot,
  and buildings that stand up and lean as you pan. Further out, people become dots. From high up you see the
  infection as a red stain over the map, hordes on the march, refugee columns on the roads, squads as unit
  symbols, and the names of the towns.
- **A region that lives.** A generated city (offices and towers downtown, apartments and shops, then
  suburbs), four to six towns, villages and farms, joined by highways that bridge the rivers. Police stations,
  hospitals, power stations, fuel depots, a research lab, a bus depot, Guard armories, an army base with
  helipads and a motor pool, and a walled prison. People go out by day and home at night.
- **Hundreds of thousands of people, simulated two ways.** Everyone indoors is counted by the sector (512 px
  squares). Where something is happening (the dead near the living, police and soldiers, or simply the camera),
  the counts come out as individuals. When it's quiet they fold back in. Every sector is simulated every second,
  watched or not, so a town nobody is looking at can still fall.
- **The dead.** They chase what they see, follow the scent of the living round buildings, drift towards people
  and lights, claw at stopped cars, and break into buildings with people sheltering inside. Big masses in open
  country become hordes that march on the nearest people, merge, and break up when they arrive. New strains
  appear as the days pass: runners, then armoured, then stalkers that hunt at night.
- **The response.** People call 911 (after a while, and later when the dead are only indoors). Police answer,
  nearest first. When a town has too many of the dead, its police throw a cordon of posts round the infected
  streets and the army goes in. Squads fall back together when pressed, restock at their station, and come back.
  Gunships orbit big fights, tanks go to the biggest one, air strikes hit masses of the dead in open country,
  supply trucks bring ammunition from the base, and engineers blow bridges in front of hordes.
- **A crisis that grows.** The alert level rises from Advisory to State of Emergency, Martial Law and National
  Mobilisation. Each step brings help by the highways: state police, then the National Guard, then the regular
  army with armour. Lose a power station and its towns go dark (and the dead go for the lights). Lose a fuel
  depot and its cars stop. Hospitals fill with the bitten. Rumours cost public trust, and fewer people do as
  they're told. Curfews empty the streets; evacuations send columns of refugees down the highways to safer
  towns, if the roads stay open.
- **Three ways to play.**
  - *Sandbox*: every tool. Paint the dead (walkers, runners, brutes, armoured, giants, or hordes of 2,000),
    place police and soldiers and vehicles, call air strikes, blow bridges, give orders, evacuate towns.
    Scenarios: an open region, a lab leak, a crashed airliner, a prison riot, a summer festival.
  - *Commander*: you make the calls (orders, a limited number of air strikes, bridges, evacuations, curfew) with
    a goal and a deadline: hold the city for three days, evacuate the towns, retake the region, protect the
    capital.
  - *Survivor*: you are one person in the city when it starts. Drag the stick to move; you shoot by yourself when
    armed. Survive two days or reach the army base.
- **Readable at every scale.** Tap anyone or anything: names, ranks, ammunition, what they're doing, their
  squad's orders, a vehicle's crew and fuel, a power station's grid, a town's people and dead. The radio log
  keeps every message (tap one to go there). The after-action report has a timeline, the turning point, the
  numbers, the best fighters, what happened, and the fallen.

## Controls

Drag to move, pinch to zoom, tap to look. The **Map** button jumps between the whole region and where you were;
the minimap does the same with a tap. Top right: pause and speed (1x to 16x; an hour of game time passes in a
minute at 1x). The menu button (or the phone's back button) pauses.

## Building and testing

The game is plain Java with no libraries and no game engine.

```
./test.sh              # the test suite, on a desktop JVM (about a minute)
./test.sh --ui         # plus a scripted run through the whole interface, saving screenshots to build/uishots
./build.sh             # the signed APK, into release/ZombieCitySandbox2.apk
./play.sh              # the game in a desktop window (mouse = finger, wheel = zoom, Esc = back)
```

`build.sh` needs the Android command-line tools: on Ubuntu or Debian,
`sudo apt-get install aapt dalvik-exchange zipalign apksigner android-sdk-platform-23`.
The tests and `play.sh` only need Java 8 or newer. CI (`.github/workflows/build-apk.yml`) runs the tests and
builds the APK on every push.

Headless tools for measuring (run from the repository root, with `build/test` on the classpath):
`Probe` (an outbreak of any size, printing the numbers and simulation time every 30 s),
`Shot` (the world drawn at five zooms), `UiShot` (the interface driven by scripted taps).

## Changing the game: content.ini

Every person, weapon, vehicle, zombie type and scenario is defined in [`assets/content.ini`](assets/content.ini),
not in code: speeds, health, armour, aim, nerve, weapon ranges and rates of fire, blast sizes, vehicle seats and
guns, how common each kind of the dead is and which strain brings it, and each scenario's start, size, hordes,
goal and deadline. Add a section to add a type.

## Layout

```
src/core/      the whole game, platform-free (simulation, rendering through Gfx, interface, audio synthesis)
src/android/   the Android shell: activity, view, Canvas drawing, AudioTrack, files and settings
desktop/src/   the desktop shell, tests, probes and screenshot tools
assets/        content.ini
docs/          DESIGN.md (how it works and why) and the first game's design brief
```

See [`docs/DESIGN.md`](docs/DESIGN.md) for how the simulation works, and how it answers the lessons of the
first game.
