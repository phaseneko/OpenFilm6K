# Rewindpix custom firmware for OpenFilm6K — agent runbook

This document is the complete, self-contained procedure for building and installing the
OpenFilm6K companion firmware on a **Rewindpix PS135** camera. It expects no prior knowledge.

> Current for release **v0.12.5**. All hashes below match the `patch_rewindpix.py` shipped
> with that release. The script verifies every input and output itself and refuses anything
> unexpected — treat its success lines as the authority over the hashes printed here.

## How to use this document (agent operating contract)

If a user hands you this document with **no other instructions**, do exactly this, in order:

1. **Build autonomously** — run §2, §3, §4 and §5 yourself, end to end, without asking the
   user anything. Asking is only allowed when a verification step fails in a way §4 does not
   cover, or when a prerequisite from §1 is missing.
2. **Deliver** the two firmware files of §5 with their SHA-256 hashes.
3. **Then** present the disclaimer (§0) and the rollback / emergency procedures (§8) to the
   user in your own words, and get their explicit consent. Nothing has touched the camera up
   to this point — the build only produces files.
4. Only after consent, guide the user interactively through installing (§6) and Wi-Fi setup
   (§7), and close with the checklist (§9).

You never operate the camera yourself: §6–§8 are the user's hands, your instructions.

## 0. Disclaimer — present to the user before any camera-side step

The firmware built here is **not official Rewindpix software**. State clearly and get the
user's explicit consent before guiding any camera-side step:

- This is community-made, unsupported software. Rewindpix is not involved in it.
- Flashing any firmware carries a **risk of rendering the camera unusable ("bricking")**.
- Modifying the firmware **may void the camera's warranty**.
- The authors of OpenFilm6K accept **no liability** for damaged hardware, lost data or lost
  warranty. Everything is done at the user's own risk.
- Risk mitigations: the build script refuses every input it does not recognize byte-for-byte;
  the official firmware re-flashes over the custom build (§8); and there is an emergency boot
  path that skips card updates entirely (§8).

## 1. What you need

- A Rewindpix PS135 camera and its SD card (FAT32), plus an SD-card reader.
- A computer with internet access, `curl` (or a browser), `unzip`, and **Python 3**
  (standard library only; no pip packages).
- The Android phone with the **OpenFilm6K Host** app installed (needed from §7 on) — it comes
  from https://github.com/phaseneko/OpenFilm6K/releases if missing.

## 2. Download the official firmware (agent)

The patch is built **on top of the user's own copy of the official firmware**; OpenFilm6K
redistributes no official image.

1. Open the official page: **https://rewindpix.com/pages/faq-spec-manual**
2. In the **“Firmware upgrade”** section download **`Misfire_FW.zip`**.
   Headless (browser-less) agents: fetch the direct link —
   `https://cdn.shopify.com/s/files/1/0928/9724/7554/files/Misfire_FW.zip?v=1790319975`
   — and if that URL ever stops working, re-scrape the current link from the page itself:

   ```bash
   curl -sL https://rewindpix.com/pages/faq-spec-manual \
     | grep -oE 'https://cdn\.shopify\.com/s/files/[^"]*Misfire_FW\.zip[^"]*' | head -1
   ```
3. Unzip it. The needed file is `Misfire FW/FW_X94A.bin` (note the space in the folder name —
   quote the path in shell commands).
4. Verify it is exactly the expected version:

```bash
ls -l "Misfire FW/FW_X94A.bin"        # size must be 3304240
sha256sum "Misfire FW/FW_X94A.bin"    # 2448d407cc558b8be4511cb3e6d7ffa4540efe4c34e49dd9d56ccb3c72dbab09
```

**Do not use** the other firmware packages on that page (for example `FLASH_WB_update.zip`
or `KILL_LAG_update.zip`): they carry different firmware versions and the build script
refuses them.

## 3. Download the OpenFilm6K firmware patch (agent)

Latest release asset:

```
https://github.com/phaseneko/OpenFilm6K/releases/latest/download/patch_rewindpix.py
```

Pinned copy of v0.12.5 (use this when exact reproducibility matters):

```
https://github.com/phaseneko/OpenFilm6K/releases/download/v0.12.5/patch_rewindpix.py
```

Verify (v0.12.5 copy: 20,924 bytes):

```bash
sha256sum patch_rewindpix.py   # 36ab5c7f7c9ec4a04192dd8e403bc2d1e993f0162ff77e5e68ad1867e2cb5038
```

If the **latest** asset's size or hash differs from the values above, a newer release has
shipped — fall back to the pinned v0.12.5 URL, whose hash is the one documented here. If
even the pinned copy no longer matches its documented hash, the release assets were replaced:
**stop and report** — a fresh OpenFilm6K release is needed; do not proceed with an
unverifiable script.

## 4. Build the patched firmware (agent)

Run in the directory holding both files:

```bash
python3 patch_rewindpix.py "Misfire FW/FW_X94A.bin"
```

Expected output — all four lines, no errors:

```
OFFICIAL 1.2.1D OK
patched RTOS OK
output: FW_X94A_patched.bin  size=3304240  sha256=a18b0d67cecca9407350ffcfe4ba8bf3939d73ff861c780574743c1092454180
matches hfs43 OK
```

Verify independently:

```bash
sha256sum FW_X94A_patched.bin   # a18b0d67cecca9407350ffcfe4ba8bf3939d73ff861c780574743c1092454180
```

**Failure handling.** The script stops by itself with a clear `ERROR:` line and writes no
output when the input is the wrong version (size/SHA mismatch) or was modified:

- Wrong package (e.g. FLASH_WB, KILL_LAG): re-download the correct `Misfire_FW.zip` per §2.
- Correct package but hash still mismatches: the vendor has likely replaced the package with
  a newer firmware version. **Stop and report** — this patch is built for exactly the pinned
  version; a newer official version needs an updated OpenFilm6K patch. Do not retry in a
  loop, and never try to edit the official file by hand.

## 5. Deliverables (agent)

Prepare a delivery folder for the user containing exactly:

| File | Content | Purpose |
|---|---|---|
| `FW_X94A.BIN` | the patched firmware (renamed copy of `FW_X94A_patched.bin`) | copy to the SD-card root to install (§6) |
| `FW_X94A_OFFICIAL.BIN` | the untouched official `FW_X94A.bin` | keep for rollback (§8) |

```bash
mkdir -p delivery
cp FW_X94A_patched.bin delivery/FW_X94A.BIN
cp "Misfire FW/FW_X94A.bin" delivery/FW_X94A_OFFICIAL.BIN
sha256sum delivery/*
# FW_X94A.BIN        a18b0d67cecca9407350ffcfe4ba8bf3939d73ff861c780574743c1092454180
# FW_X94A_OFFICIAL.BIN 2448d407cc558b8be4511cb3e6d7ffa4540efe4c34e49dd9d56ccb3c72dbab09
```

Report both hashes to the user, state that the official copy is byte-identical to the
vendor's published image, then continue with step 3 of the operating contract (§0 + §8,
consent) before any camera-side step.

## 6. Install the firmware on the camera (user hands, agent guides)

1. On the SD card, **delete any old `HFS43.TXT`** in the root first — the success check
   below must not be fooled by a marker left over from an earlier install.
2. Copy `delivery/FW_X94A.BIN` to the **root of the camera's SD card**. The name must stay
   exactly `FW_X94A.BIN` (8.3 short name, upper case); replace any older same-name file.
3. Make sure the camera battery is not empty, insert the card, power the camera on. It
   installs the file by itself while booting.
4. **Success check:** after the camera has fully started, power it off, move the card back
   to the reader: a fresh **`HFS43.TXT`** in the card root confirms the new firmware is
   running. This marker is written by this build only.

If the camera behaves exactly like before and no fresh marker appears, the file name on the
card was wrong (recheck step 2) or the camera did not read the card.

## 7. Configure the Wi-Fi connection (user hands, agent guides)

The camera joins the **phone's hotspot (2.4 GHz band)** to talk to the OpenFilm6K Host app.

1. Create `wifi.txt` with exactly two lines — hotspot name, then its password (each up to 26
   characters). Create it with a command so the format is guaranteed (plain text, LF line
   endings, no BOM):

```bash
printf 'MyPhoneHotspot\nthepassword\n' > wifi.txt     # replace both values
```

2. Put `wifi.txt` in the **card root**, reinsert the card, power the camera on.
3. **Long-press the Wi-Fi key** on the camera so it joins the hotspot.
4. On the phone: open OpenFilm6K, tap the 🎞️ **Rewind** button in the title bar, switch on
   **Rewind sync**. The camera is detected automatically once connected; film slots,
   watermark and post-processing settings are pushed to it, and photos are pulled back the
   same way.

Without a `wifi.txt` the camera stays in its own access-point mode; rename or delete the
file and reboot to return to that mode.

If the camera does not join: re-create `wifi.txt` with the command above (rules out editor
problems), confirm the hotspot is 2.4 GHz with a visible name and a password of at most 26
characters, and long-press the Wi-Fi key again.

## 8. Rollback and emergency recovery (present together with §0)

**Rollback to official firmware (camera works):** copy `FW_X94A_OFFICIAL.BIN` to the card
root **renamed to `FW_X94A.BIN`** (same short name — there is only room for one firmware
file), then boot the camera: it reinstalls the official firmware over the custom one. Also
delete the `HFS43.TXT` marker afterwards so later success checks start clean.

**Emergency (camera stays black / never finishes booting after an update):** power the
camera off, remove the SD card, and on the computer **delete every `FW_X94A*` file from the
card root**. Reinsert the card and power on: with no firmware file present the camera boots
the firmware already installed in its flash, skipping any update. From there either retry
§6 with a correctly built file or roll back to official as above.

**Build problems:** see §4 — always a wrong or modified official input file; never edit the
firmware files by hand.

## 9. Acceptance checklist (go through with the user)

- [ ] §0 disclaimer shown and consent given
- [ ] official `FW_X94A.bin` matched size 3304240 and SHA-256 `2448d407…`
- [ ] build printed all four success lines; patched file SHA-256 `a18b0d67…`
- [ ] delivery folder holds `FW_X94A.BIN` and `FW_X94A_OFFICIAL.BIN` with matching hashes
- [ ] card root holds `FW_X94A.BIN`; after boot a **fresh** `HFS43.TXT` appeared
- [ ] `wifi.txt` (two lines) in card root; camera joined the hotspot after a long press on
      the Wi-Fi key
- [ ] OpenFilm6K Rewind sync on; the camera shows up and the settings were pushed
- [ ] a test shot arrives on the phone and is rendered into the album
