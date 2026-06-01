# Dungeon Music

Status: Current
Last verified: 2026-06-01
Source docs: environmental-background-music task
Verified against: src/main/java/com/duntale/audio/BackgroundMusicService.java, src/main/java/com/duntale/DuntalePlugin.java, src/main/resources/Server/Audio/MusicContainers/Playlists/Duntale/MC_Duntale_Dungeon_Rotation.json, src/main/resources/Server/Audio/MusicContainers/Tracks/Duntale/Dungeon/, src/main/resources/Common/Music/Duntale/Dungeon/, src/test/java/com/duntale/audio/BackgroundMusicServiceTest.java

## Purpose

Document the current dungeon background-music setup and the exact authoring flow for adding new songs to the shipped dungeon playlist.

## Current State

- Duntale currently forces one dungeon playlist asset: `MC_Duntale_Dungeon_Rotation`.
- `BackgroundMusicService` resolves that playlist asset id and applies it whenever a player is inside a resolved dungeon world.
- The live playlist is stored at `src/main/resources/Server/Audio/MusicContainers/Playlists/Duntale/MC_Duntale_Dungeon_Rotation.json`.
- The playlist uses `Type: Random`, `AudioCategory: AudioCat_Music`, `Mode: Random`, `LoopCount: 0`, and `AvoidRepeatCount: 3`.
- Each playlist child inherits from a reusable `SingleTrack` music-container asset under `src/main/resources/Server/Audio/MusicContainers/Tracks/Duntale/Dungeon/`.
- Each track asset points at a final `.ogg` file under `src/main/resources/Common/Music/Duntale/Dungeon/`.
- The repository currently ships ten dungeon songs:

| Track asset id | OGG path |
| --- | --- |
| `Track_Duntale_Dungeon_Cursed_Chambers_Fight` | `Music/Duntale/Dungeon/Cursed_Chambers_Fight.ogg` |
| `Track_Duntale_Dungeon_Dungeon_Depths` | `Music/Duntale/Dungeon/Dungeon_Depths.ogg` |
| `Track_Duntale_Dungeon_Dust_And_Amber_Light` | `Music/Duntale/Dungeon/Dust_And_Amber_Light.ogg` |
| `Track_Duntale_Dungeon_Echoes_Of_The_Abyss` | `Music/Duntale/Dungeon/Echoes_Of_The_Abyss.ogg` |
| `Track_Duntale_Dungeon_Echoes_Of_The_Labyrinth` | `Music/Duntale/Dungeon/Echoes_Of_The_Labyrinth.ogg` |
| `Track_Duntale_Dungeon_Rootbound_Ruins` | `Music/Duntale/Dungeon/Rootbound_Ruins.ogg` |
| `Track_Duntale_Dungeon_Shadows_In_The_Stone` | `Music/Duntale/Dungeon/Shadows_In_The_Stone.ogg` |
| `Track_Duntale_Dungeon_Shadows_In_The_Vault_Loop` | `Music/Duntale/Dungeon/Shadows_In_The_Vault_Loop.ogg` |
| `Track_Duntale_Dungeon_The_Hourglass_Breaks` | `Music/Duntale/Dungeon/The_Hourglass_Breaks.ogg` |
| `Track_Duntale_Dungeon_The_Gates_Last_Stand` | `Music/Duntale/Dungeon/The_Gates_Last_Stand.ogg` |

- The checked-in playlist currently adds a `SilenceAfter` window of `60-180` seconds to every child entry.
- The repo stores only the final shipped `.ogg` files. Source WAV or DAW exports are not tracked here.

## Add A New Song

1. Prepare the final shipped audio file.
   Put the committed file under `src/main/resources/Common/Music/Duntale/Dungeon/` and follow the current naming style, for example `My_New_Track.ogg`.

2. Create a reusable track container asset.
   Add a JSON file under `src/main/resources/Server/Audio/MusicContainers/Tracks/Duntale/Dungeon/` named `Track_Duntale_Dungeon_<SongName>.json`.

   Example:

   ```json
   {
     "Type": "SingleTrack",
     "AudioCategory": "AudioCat_Music",
     "LoopCount": 1,
     "Track": "Music/Duntale/Dungeon/My_New_Track.ogg"
   }
   ```

3. Add the new song to the dungeon playlist.
   Update `src/main/resources/Server/Audio/MusicContainers/Playlists/Duntale/MC_Duntale_Dungeon_Rotation.json` and append a new child that inherits from the track asset.

   Example:

   ```json
   {
     "Type": "SingleTrack",
     "Parent": "Track_Duntale_Dungeon_My_New_Track",
     "SilenceAfter": {
       "Min": 60,
       "Max": 180
     }
   }
   ```

4. Keep the playlist id stable unless you intend to change Java wiring.
   The runtime code looks up `MC_Duntale_Dungeon_Rotation` directly in `BackgroundMusicService`. If you only want to add songs, update the playlist children and leave the playlist asset id unchanged.

5. Only change Java if the playlist identity changes.
   If Duntale should point at a different top-level playlist asset, update `BackgroundMusicService.DUNGEON_CONTAINER_ID` and revalidate dungeon entry plus floor transitions.

## Validation

- Run `./gradlew test --tests com.duntale.audio.BackgroundMusicServiceTest` to confirm the forced-music service still resolves and reapplies the dungeon playlist correctly.
- Run `./gradlew compileJava` to catch resource-id or integration drift in the Java wiring.
- Manually verify these runtime cases after deploy:
  - Enter a dungeon from the village and confirm the custom playlist starts.
  - Transition from floor `N` to floor `N + 1` and confirm the playlist is restored on the new floor.
  - Leave the dungeon and confirm forced music clears back to default world music.

## Known Gaps

- There is no automated asset test that checks whether every playlist child points at an existing `.ogg` file.
- The repository does not currently include a checked-in source-audio conversion pipeline. Converting source audio into the final shipped `.ogg` remains a manual content step.
- The runtime currently assumes all dungeon floors use the same top-level playlist asset.

## Related Docs

- [Dungeon Instances](../systems/dungeon-instances.md)