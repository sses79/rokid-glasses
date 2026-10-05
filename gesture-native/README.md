# Gesture Lab (native)

A hand-gesture test app for Rokid Glasses. The glasses' camera watches your
hand, MediaPipe Hands finds its 21 landmarks on the device, and the app shows
which emoji gesture you are making. It also records test clips so the gesture
rules can be tuned and regression-tested on the Mac.

It is a standard Android app (Rokid calls this "bare-metal" development), not an
AIUI agent. The AIUI camera API delivers about one frame per second; this app
gets 14–28.

## Gestures

| | Gesture | How |
|---|---|---|
| 👍 | Thumbs up | Fist, thumb pointing up |
| 👎 | Thumbs down | Fist, thumb pointing down |
| ← | Left | Fist, thumb pointing to your left |
| → | Right | Fist, thumb pointing to your right |
| ✊ | Fist | Fist, thumb tucked in |
| ✋ | Raised hand | All four fingers straight |
| ✌️ | Victory | Index and middle straight, ring and little curled |
| 🤟 | Love you | Thumb, index and little finger out |
| 👌 | OK | Thumb and index tips touching, ring and little finger straight |

Left and right are your own left and right; nothing is mirrored. There are no
motion gestures (swipes): the camera moves with your head, so a head turn looks
the same as a hand sweep.

## Using it on the glasses

A single tap on the right temple goes to the next page. A double-tap (Back)
closes the app.

| Page | What it does |
|---|---|
| EMOJI | Live hand skeleton, the detected gesture, and the full gesture row |
| TILES | A cursor follows your palm; pinch thumb and index to toggle a tile |
| DIAL | Pinch and move sideways to drag a value |
| CAPTURE | Records one test clip per gesture (see below) |

## Requirements

- Rokid Glasses on firmware 1.26.011 or later, connected over USB, with the
  developer/ADB switch enabled in the Hi Rokid phone app.
- `adb` on the PATH (`brew install --cask android-platform-tools`).
- Android SDK at `/opt/homebrew/share/android-commandlinetools`
  (`brew install --cask android-commandlinetools`, then accept the licenses and
  install `platforms;android-35` and `build-tools;35.0.0` with `sdkmanager`).
  Set `ANDROID_HOME` if yours is elsewhere, and update `local.properties`.
- JDK 17 or newer.

Always build with `./gradlew`. A system-wide Gradle 9 cannot configure this
project.

## Build, start, stop

```bash
./deploy.sh        # build, install, grant camera permission, (re)start the app
./app.sh start     # start the installed app
./app.sh stop      # close it
./app.sh restart   # close and start again
./app.sh log       # follow the app's log (fps, detected gesture, key presses)
```

`deploy.sh` grants the camera permission over USB because the glasses have no
touch screen to answer the permission dialog.

To close the app from the glasses themselves, double-tap the temple. Starting
it has only been done from the Mac so far.

## Recording test clips

1. Tap to the CAPTURE page.
2. Hold 👍 for one second. The app then walks through all nine gestures on its
   own: three seconds to get ready, four seconds recording, for each one
   (about 63 seconds in total). Tap the temple to stop early.
3. Each clip is marked PASS or FAIL on the glasses.
4. On the Mac, copy the runs over and replay them:

   ```bash
   ./pull-runs.sh
   ```

A run is saved as:

```text
tests/manual-runs/YYYY-MM-DD-HHMMSS/
  run.json     what was asked for, what the detector saw in every frame, pass or fail
  clips/       the frames the detector analysed, one .mp4 per gesture
```

`run.json` holds all 21 landmarks (x, y and depth) for every frame, so a clip
can be replayed through the gesture code without the video or the glasses.

A clip passes when the expected gesture shows in at least 60% of frames after
the first second. The first second is ignored because that is when you are
changing from the previous gesture.

`tests/manual-runs/` is git-ignored: the videos show your surroundings. Review
them before sharing.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

| Test | Covers |
|---|---|
| `EmojiClassifierTest` | Each gesture on schematic hand shapes |
| `GestureEngineTest` | Cursor smoothing, pinch start/end, the hold before a gesture shows |
| `CaptureJudgeTest` | The pass/fail rule for a clip |
| `ReplayRunsTest` | Replays recorded runs through the current code |

`ReplayRunsTest` does two things. Runs in `tests/manual-runs/` are replayed and
reported, with a note on any clip whose result changed since it was recorded.
Runs copied into `app/src/test/resources/fixtures/` must pass on every clip, so
a rule change that breaks a real recorded gesture fails the build.

To promote a run to a fixture, copy its `run.json` into that folder under any
name ending in `.json`.

### Tuning a gesture

1. Record a set and `./pull-runs.sh`.
2. Watch the failing clip in `clips/` to see what the hand was doing.
3. Change the narrowest relevant threshold in `EmojiConfig`
   (`EmojiClassifier.kt`) or `GestureConfig` (`GestureEngine.kt`).
4. Run the tests: the fixtures show whether other gestures were affected.
5. `./deploy.sh` and try it live.

## How the code fits together

```text
camera frame
   │  CameraX ImageAnalysis, 640x480
   ▼
HandTracker        turns the frame upright, runs MediaPipe Hand Landmarker (GPU),
   │               and feeds the same frame to ClipEncoder while recording
   ▼  21 landmarks
GestureEngine      smoothed palm cursor, pinch start/end, and the emoji gesture
   │               once it has been held for 150 ms
   │   └─ EmojiClassifier   one frame of landmarks -> a gesture, or none
   ▼  HandState
MainActivity       wires everything together, handles temple taps, runs the
   │               capture sequence, logs a status line every two seconds
   ▼
PlaygroundView     draws the four pages
```

| File | Role |
|---|---|
| `MainActivity.kt` | Camera setup, key handling, capture sequence, logging |
| `HandTracker.kt` | Frame rotation and MediaPipe; falls back from GPU to CPU |
| `GestureEngine.kt` | Cursor, pinch and gesture stability over time |
| `EmojiClassifier.kt` | The gesture rules and their thresholds |
| `PlaygroundView.kt` | All drawing, in green on black inside the display's safe area |
| `Capture.kt` | The gesture list for capture and the pass/fail rule |
| `CaptureRun.kt` | Writes `run.json` |
| `ClipEncoder.kt` | Writes the clip video from the tracker's frames |

`GestureEngine`, `EmojiClassifier` and `Capture` have no Android dependencies,
which is why they can be tested and replayed on the Mac.

The hand-landmark model is `app/src/main/assets/hand_landmarker.task`
(MediaPipe's float16 hand landmarker).

## Things that are specific to the glasses

- **The camera sensor is mounted sideways.** `HandTracker` rotates each frame
  upright itself. Asking MediaPipe to do the rotation returned landmarks with x
  and y swapped.
- **The camera cannot feed tracking and a standard video recorder together.**
  Clips are therefore encoded from the tracker's own frames, so a clip shows
  exactly what the detector saw.
- **The glasses see the back of your own hand.** A curled fingertip still lies
  beyond its knuckle in the image, so a finger counts as extended only if it is
  straight at the middle joint.
- **The display is 480x640, green only, and black is unlit.** Content stays
  between y=80 and y=560.
- **The Rokid assistant can pop up over the app** and take the tap input until
  it is dismissed with Back.

## Known limits

- Tuned on one person's hands in one room. Both hands pass, but other people,
  gloves, or very different lighting are untested.
- Thumb direction splits at 45°. A thumbs-down that leans far to the side can
  read as an arrow, and the reverse.
- One hand at a time; two-hand gestures are not implemented.
- Frame rate has varied between about 14 and 28 per second across sessions,
  for a reason not yet identified.
- The rules use 2D positions only. Depth is recorded in `run.json` but not used.

## Origin

The gesture ids and the capture/replay workflow follow the browser project
front-king (`capture.html`, `tests/manual-runs/`). The finger and scale rules
were then retuned on clips recorded through the glasses, and index-finger
pointing was replaced by the thumb-direction arrows.
