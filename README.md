# Rokid Glasses development

Three projects for Rokid Glasses (with display), in the order they were built.

| Folder | What it is | Status |
|---|---|---|
| [gesture-native/](gesture-native/README.md) | Native Android app: MediaPipe hand tracking, emoji gestures, test-clip capture | Active |
| `gesture-lab/` | AIUI agent that tracks a hand by skin colour | Parked: the AIUI camera gives about one frame per second |
| `hello-agent/` | The AIUI starter agent, used to prove the toolchain | Reference only |

Rokid publishes an AIUI reference skill for coding agents. It is not included
here; install it with:

```bash
npx skills add https://github.com/jsar-project/AIUI/tree/main/skills/aiui-dev
```

## Two ways to build for the glasses

**AIUI** (`hello-agent/`, `gesture-lab/`) is Rokid's JavaScript framework. An
agent is a folder of `.ink` pages, packed and installed with the `aix` CLI:

```bash
npm install -g @yodaos-pkg/aix-cli
cd hello-agent
npm start          # preview in the browser, with simulated temple controls
npm run deploy     # install on the glasses and open the page
```

After an install the glasses keep running the previous build. Press Back on the
glasses, then run `aix launch-page .` again to get the new one.

**Native Android** (`gesture-native/`) is a normal Android app installed with
`adb`. Use it when you need the camera at full frame rate. See its README.

## Getting the glasses ready

1. Update the glasses to firmware 1.26.011 or later in the Hi Rokid phone app.
2. In the same app, enable the developer/ADB switch under Settings → Developer.
3. Connect over USB and check they are listed with status `device`:

   ```bash
   adb devices
   ```

4. For AIUI only, turn on Developer Mode once: `aix device set-dev`.

With the ADB switch on, the glasses' screen and logs can be read from the Mac:

```bash
adb exec-out screencap -p > screen.png
adb logcat -s GestureLab     # native app
adb logcat -s Ink            # AIUI agents
```
