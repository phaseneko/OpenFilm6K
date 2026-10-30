# OpenFilm6K

**Film simulation for Sony A6000 cameras.**

OpenFilm6K is an open-source film-simulation system for Sony cameras that run its camera app (α6000, α7 series, NEX, RX100 III). Shoot with the camera, and your Android phone renders the final film-look photo automatically — LUT color, halation glow, grain, vignette and more, all editable in a node-graph editor. Photos arriving from any intake path (camera upload, folder watch, FTP, Rewind, Luma) can optionally be denoised on the phone before grading with an ISO or day/night condition; the untouched originals can also be kept in a separate album. The phone can also compose its own watermark template — text, rules, rectangles, photo slots and camera-brand logos — which the camera stamps onto each shot.

```
┌────────────┐   photo (HTTP)   ┌──────────────────────┐
│  A6000     │ ───────────────▶ │  Android phone       │
│  camera    │ ◀─────────────── │  render host + editor│
│  (camera/) │  film list ping  │  (host/)             │
└────────────┘                  └──────────────────────┘
```

| Directory | What it is |
|---|---|
| `host/` | Android app: render host + node-graph film editor (Java + GLES3, plus a native C++ engine) |
| `host/films/` | Bundled film stocks: `pipelines/*.properties` (node chains) + `luts/*.cube` |
| `camera/` | Android app for the camera itself: capture, manual controls, upload to the host |
| `host/assets/watermarks/` | Four sample watermark templates, installed on first launch |
| `docs/screenshots/` | Usage screenshots |

## Screenshots

### The camera app (on the A6000)

![Camera UI](docs/screenshots/camera-ui.png)

### The phone app (render host + editor)

| ![Phone app 1](docs/screenshots/phone-1.png) | ![Phone app 2](docs/screenshots/phone-2.png) | ![Phone app 3](docs/screenshots/phone-3.png) |
|---|---|---|

## Installation

**Prebuilt APKs:** [github.com/phaseneko/OpenFilm6K/releases/latest](https://github.com/phaseneko/OpenFilm6K/releases/latest)
— `OpenFilm6K-Host.apk` for the phone, `OpenFilm6K-Camera.apk` for the camera.
Building from source is described below.

### Prerequisites

- Android SDK (build-tools 30.0.3 + 36.0.0, platform android-28), NDK (a recent clang toolchain for arm64 **and** NDK r16 `ndk-build` for the camera), JDK 8–17.
- An Android phone (the render host) and a Sony A6000 with a side-load ability for camera apps (developer-friendly firmware access, e.g. the [PMCA installer](https://github.com/ma1co/Sony-PMCA-RE) or `adb` over Wi-Fi).
- The phone and the camera must be on the same Wi-Fi network (the camera connects to the phone's hotspot or shared network; the phone's IP must be the network gateway — this is how the camera finds the host).

### 1. Build the APKs

```bash
# phone host — produces OpenFilm6K-Host.apk
cd host && bash build/build.sh

# camera app — produces OpenFilm6K-Camera.apk
cd camera && bash build.sh
```

### 2. Install the phone host

Install `OpenFilm6K-Host.apk` on the Android phone like any APK, then launch it once. On first launch the app:

- starts the render host on port **8800** (a persistent status-bar notification shows it is running),
- creates its data folders on the SD card.

### 3. First launch

Two permissions are involved on first launch:

- **File access (required)** — on first start the app asks for storage access (**All files access** on Android 11+; a plain storage permission on older versions). Grant it: without it the bundled film library cannot install.
- **Accessibility service (optional)** — if you want the app to stay resident in the background (recommended when using it as the camera's render host), enable it in the system settings: **Settings → Accessibility → OpenFilm6K**.

After file access is granted, the film library (19 film pipelines, LUTs and scene previews) — bundled **inside the host APK** — installs itself automatically: a progress dialog writes it to `/sdcard/OpenFilm6K/`, and a result dialog reports the file count. The install is **incremental**: only missing files are written, so existing edits and custom films stay as they are. After upgrading the APK you can pull in newly bundled files from **Settings → Reinstall data** (same progress dialog, also incremental).

### 4. Install the camera app on the A6000

Side-load `OpenFilm6K-Camera.apk` onto the camera ([PMCA installer](https://github.com/ma1co/Sony-PMCA-RE), or `adb connect <camera-ip>:5555 && adb install -r OpenFilm6K-Camera.apk`). Launch it from the camera's application list.

The camera app needs **no film assets on the camera** — it pulls the film list from the phone at runtime and uploads every shot for rendering.

> Note: the camera's SD-card filesystem (Sony FuFsys) only lets the app write into pre-existing directories. The app writes its log (`LOG.TXT`) to the first writable folder (`OpenFilm6K`, `DCIM/OpenFilm6K`, `DCIM`).

## Using the camera app

The camera app is controlled entirely with the camera's physical keys (no touchscreen).

### Boot & connection

First connect the camera to the Wi-Fi hotspot of the phone that runs the host app. On launch the app joins the network, pings the phone host (`:8800`), pulls the film list, and shows the live viewfinder. The HUD shows film, exposure, battery and AF status.

### Keys

Every action is available on every supported body — only the key that carries it
differs: **buttons down the left, bodies across the top.**

| Button | α6000 | α7 series | NEX series | RX100 III |
|---|---|---|---|---|
| Shutter ½ | auto-focus (center / spot) | auto-focus | auto-focus | auto-focus |
| Shutter full | shoot → grade → upload | shoot → grade → upload | shoot → grade → upload | shoot → grade → upload |
| ↑ ↓ ← → | move the highlight (Mode / Shutter / Aperture / ISO / EV / Film) | move the highlight | move the highlight | move the highlight |
| Center | cycle camera mode (P→A→S→M); in the film browser, load the film | cycle camera mode; in the film browser, load the film | confirm / cycle camera mode; in the film browser, load the film | short: enter/exit focus-area adjust · long: recentre the focus point; in the film browser, load the film |
| Fn | open the film browser | open the film browser | short: open the film browser · long: cycle favourite films · in the film browser: toggle favourite | open the film browser · in the film browser: toggle favourite (the menu stays open) |
| AEL | short: enter/exit focus-area adjust · long: screen-lock (3 s countdown, any key wakes) | short: focus-area adjust · long: screen-lock | “Soft key A”: short: focus-area adjust · long: screen-lock | — |
| C1 | hold + a dial: cycle favourite films; in the film browser: toggle favourite | hold + a dial: cycle favourite films; in the film browser: toggle favourite | — (merged into Fn) | — |
| C2 | short: open the watermark & post-process menu · long: switch LCD/EVF view | short: open the watermark & post-process menu · long: switch LCD/EVF view | “Soft key B”: short: open the menu · long: switch LCD/EVF view | short (delete key): open the menu · long: switch LCD/EVF view |
| C3 | — | quick AF-area-mode switch | — | — |
| C4 | — | same as C2 | — | — |
| MENU | quit the app | quit the app | — | — |
| PLAY | — | — | quit the app | quit the app |
| Front dial | — | select film | — | — |
| Rear dial | exposure compensation (EV) | exposure compensation (EV) | exposure compensation (EV) | — (no rear dial) |
| Control wheel | adjust the highlighted parameter | adjust the highlighted parameter | adjust the highlighted parameter | adjust the highlighted parameter |
| Zoom lever | — | — | — | W/T: optical zoom (wide / tele) |

Inside focus-area adjust the **centre key** cycles the metering mode the body offers
(RX100 III: long press of the centre key); the HUD shows the current mode.

The watermark menu (C2 / Soft key B / RX100 delete key, short press) has two columns:
left = watermark and frame modes, right = post-processing. The status bar shows a symbol:
*(blank)* none · **D** date · **E** exposure · **DE** date + exposure · **B**
white-card frame · **X** four-film collage · **F** film name · **FE** film name +
exposure · **USR** custom template. Templates composed on the phone appear at the top of the left
column. Exposure = shutter / aperture / ISO, e.g. `1/60 F2.8 ISO400`.

- The NEX series and the RX100 III have no MENU key; on both, `PLAY` quits the
  app (handled by the firmware). The NEX's C1 action is merged into Fn.
- RX100 III: no rear dial (the control wheel adjusts the highlighted parameter)
  and no AEL / C1 / C3 / C4. Focus-area adjust therefore lives on the **centre
  key** (short press enters/exits it, long press recentres the point; in that
  mode the arrows move the point, the control wheel cycles the AF-area mode and
  C1 exits to the film row). Screen-lock and the C1 favourite mode remain
  unavailable; the delete key acts as C2 and the W/T lever drives the optical
  zoom. Keep the still format at JPEG (RAW is not supported;
  the app switches it back automatically for itself and does not change the
  camera's own setting).
- `MOVIE` is consumed by the camera firmware before the app sees it.

### Film browser (FN)

Lists `random`, `favorite` and all films pulled from the phone. `>` marks the cursor, `*` the loaded film. ENTER loads a film; it becomes the default for subsequent shots.

### Shooting flow

Every full press: focus → capture → the photo is POSTed to the phone (`/ingest`) with the selected film and watermark mode → the phone renders it through the film pipeline and stores the graded result in the gallery album **OpenFilm6K** (`DCIM/OpenFilm6K`). The phone's status-bar notification updates with the graded thumbnail, the film name, the exposure and the capture time — tap it to open that photo in the gallery.

If the phone is unreachable the camera keeps the originals on its SD card and retries automatically after reconnecting.

## Using the phone app

### Desktop & first launch

Launching OpenFilm6K shows the editor. The desktop area carries the app icon, a one-line description, and the author/links row.

### The editor

The editor edits **film pipelines**: each film is a chain of nodes; each node is one processing step.

- **Film & Scene group** — film selector (combobox), `New film` (blank or copy-of-current, any language name), `Delete film` (user films only; official films are locked), `Save to film` (writes the whole chain back), `Reload` (discard edits), and the scene selector (reference scenes + your own imported photos).
- **Day / Night group** — a film can carry **two calibrations**: `Day/night split` copies the current parameters into day and night halves (tabs ☀ Day / 🌙 Night). After saving, the host picks the right half automatically at ingest time; `⏱ Day/Night preview` compares both. The `Day/night` score line estimates how day- or night-like the loaded photo is.
- **Node ops group** — `Add node` (choose type: LUT color, Grade, Glow, Grain, Vignette, Sharpen, Overlay, Hue shift), `⧉ Copy config` / `📋 Paste config` for whole chains, per-node `Hide/Show` toggle, node deletion, and per-node parameter sliders.
- **Parameters** — every node exposes its parameters as sliders: exposure, contrast, saturation, temperature/tint, shadows/mids/highlights, glow threshold & radius, grain size/mono, vignette range, overlay blend & scale, hue presets, etc.
- **Settings dialog (⚙)** — import **custom reference photos** (jpg) and **custom LUTs** (`.cube`), delete them, switch the **language** (10 languages), preview the **watermark stamp** styles.
- **Preview** — the main viewer shows the original (left) and the graded result (right); drag the divider to compare. Annotation mode: drag a box on the image to attach a note.

### Watermark templates

The **watermark template editor** is the tool for building your own watermark templates:
a template is a small canvas with a photo slot, text, rules, rectangles and a camera-brand
logo, with colour, blend, opacity, shadow and soft or torn edges per element. Four sample
templates come with the app, and the template list, the template in use and the camera menu
all follow the same order.

Brand logos are not shipped with the app. The editor offers to download them the first time
they are needed (over your own connection, with a progress bar); afterwards a logo follows
the shot's EXIF brand automatically and can be switched per element.

### Monitor mode

Watches folders on the phone and renders every photo that lands in them — no camera app
needed. Tick **Enable spy mode** in its dialog, then choose the film, the watermark/frame and
the post-processing; incoming files are rendered exactly like a camera-app shot and appear in
the notification. Watched folders are added one at a time, with two shortcuts for the common
locations: the camera folder and the **LUMIX Lab** save folder. Paired modes (half-frame,
double exposure) wait for the second frame before rendering.

### Luma mode

For Panasonic bodies that pair with the phone through **Lumix Sync**: the camera publishes its
own Wi-Fi access point and the phone pulls the new shots from it.

1. On the camera open its Wi-Fi menu and start the access point: **Wi-Fi → Wi-Fi Function →
   New Connection → Remote Shooting & View** (the exact wording differs slightly between
   bodies). The camera shows the network name and password it publishes.
2. In Luma mode on the phone type exactly that network name and password, then tap
   **Transfer**.
3. The phone joins the camera network, pulls everything that arrived since the last
   transfer, renders it like a camera-app shot and returns to its previous network
   automatically — it is offline while pulling.
4. The bars show what was transferred; **No new photos** means the card holds nothing newer.

Newer bodies that work with **Lumix Lab** instead hand their photos to that app: save them
into the LUMIX Lab folder on the phone and use **Monitor mode** with the "Add LUMIX Lab save
folder" shortcut — every new file is rendered the same way.

### Notification photo inbox

Each successful ingest updates the standing notification: graded thumbnail on the left, film name, exposure (`1/125s F3.5 ISO400`) and capture time on the right. Tapping opens that photo in the system gallery.

### FTP mode (cameras that cannot run the camera app)

Cameras without PlayMemories support can use the films too: they upload their shots over **FTP** and the phone treats each one like a camera-app shot.

- Tap the FTP button in the title bar (left of the spy button), or switch on **FTP mode** in its dialog. The dialog shows the phone's address, port (2121), user and password, the same film / watermark & frame / post-process pickers as spy mode, and a refresh button that updates the address.
- On the camera enable `FTP Transfer Func.`, enter those values in its server settings (passive mode and auto upload on). Every uploaded shot is rendered with the selected film, stored in the gallery album and shown in the notification.
- Cameras with Sony FTP transfer include the A1, A9 II, A7S III, A7 IV, A7R V, A7C II, A7C R, A6700, FX3, FX30 and ZV-E1.
- The port is 2121 because port 21 requires root; set the same port on the camera.
- Keep the camera on a normal colour look: a B&W look is baked into the file and cannot be graded again.

### Rewindpix camera (Rewind mode)

The stock Rewindpix PS135 firmware does not carry the sync support the phone needs. Every
release page includes a small script that derives that build from **your own** copy of the
official firmware; it stores only the differences, so no official firmware is redistributed.

On the phone, Rewind mode keeps a roll in sync with that camera: enable **Rewind sync**, fill
the three film slots and the three stamp + post-processing combos, and set the labels the body
shows (camera label up to 10 uppercase characters, combo labels up to 8). Once the camera
joins the phone's hotspot the settings are pushed automatically; **Sync now** pushes them
again, **Pull all photos** takes everything on the card, **Refresh** rescans for the camera,
and the panel shows the camera status, the last sync and the transfer progress.

1. Get `patch_rewindpix.py` from the release page and have your own official 1.2.1D firmware
   file (`FW_X94A.bin`) at hand.
2. With Python 3 installed run `python3 patch_rewindpix.py FW_X94A.bin`. The script verifies
   the official file byte for byte, refuses anything else, and writes
   `FW_X94A_patched.bin`.
3. Copy that file to the root of the camera's SD card under the short name `FW_X94A.BIN` and
   power the camera on — it installs the file itself on boot. A `HFS43.TXT` file appearing
   on the card afterwards confirms the new build is running.
4. Tell the camera which network to join: put a plain-text `wifi.txt` in the SD card root
   with two lines — the phone hotspot's name (2.4 GHz) and its password (up to 26
   characters), e.g.

   ```
   MyPhoneHotspot
   thepassword
   ```

   Without that file the camera stays in its own access-point mode; rename or delete
   `wifi.txt` and reboot to go back to it.
5. Power the camera on and long-press the Wi-Fi key so it joins the phone's hotspot. With
   **Rewind mode** enabled on the phone the roll, watermark and post-processing settings are
   pushed automatically, and shots are pulled back the same way.
6. Keep your official firmware file: copying it to the card the same way restores the camera.

### Films

A set of film simulations is bundled (CV-250, CV-500, ET-100, ET-64, PR-100, CC-200, CS-800T, EV-500, FC-400, GD-200, LK-200, NC-200, PAN-100, PI-100, PT-400, PX-I, PX-II, RF-400, RF-800, SP-200, UM-400). You can create your own films in the editor, or import extra `.cube` LUTs via the settings dialog.

## Building from source / repo layout

Both apps are built with plain `aapt`/`javac`/`d8`/NDK pipelines — no Gradle required. See `host/build/build.sh` and `camera/build.sh`.

## Third-party components

| Component | Used for | Where | License |
|---|---|---|---|
| [libjpeg-turbo](https://github.com/libjpeg-turbo/libjpeg-turbo) | JPEG codec in the host's native render engine | `host/jni/turbo/` | BSD-style + IJG |
| [IJG libjpeg 9e](https://www.ijg.org) | JPEG codec in the camera app | `camera/jni/libjpeg/` | IJG License |
| [DSEG fonts](https://github.com/keshikan/DSEG) | 7/14-segment display typefaces for the HUD and readouts | `camera/assets/`, `host/res/raw/` | SIL OFL 1.1 |
| Additional typefaces (Zhi Mang Xing, Great Vibes, Smiley Sans, Zhuque Fangsong, Poppins, Playfair Display, Libre Baskerville, Lora, EB Garamond, Bebas Neue, Oswald, JetBrains Mono, Archivo Black, Liberation Sans, WenQuanYi Zen Hei) | Watermark card typography | `host/res/raw/` | SIL OFL 1.1 (WenQuanYi: GPL-2.0 with font exception) |

Full license texts: [`THIRD_PARTY/`](THIRD_PARTY/) — every bundled typeface's licence is also readable inside the app.

## License

GPL-3.0 — see [LICENSE](LICENSE).

## Author

NekoV — phaseneko@gmail.com — github.com/phaseneko

## Other languages

[简体中文说明](README-cn.md)
