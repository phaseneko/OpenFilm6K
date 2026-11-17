# Rewindpix custom firmware for OpenFilm6K — agent runbook

This document is the complete procedure for preparing and installing the OpenFilm6K companion
firmware on a **Rewindpix PS135** camera. It expects no prior knowledge.

> Current for release **v0.12.9**. The released `patch_rewindpix124.py` builds on the user's
> own copy of the official **V1.2.4** firmware and downloads the official updater when needed.
> OpenFilm6K redistributes no official firmware.

## How to use this document (agent operating contract)

If a user hands you this document with **no other instructions**, do this in order:

1. **Build autonomously** — run §2 and §3, without asking the user anything. Asking is only
   allowed when a verification step fails.
2. **Deliver** the patched firmware from §3 with its SHA-256 hash.
3. **Then** present the disclaimer (§0) and the rollback / emergency procedures (§6) in your
   own words and get explicit consent. Nothing has touched the camera up to this point.
4. Only after consent, guide the user interactively through installing (§4) and Wi-Fi setup
   (§5), and close with the checklist (§7).

You never operate the camera yourself: §4–§6 are the user's hands, your instructions.

## 0. Disclaimer — present to the user before any camera-side step

The firmware built here is **not official Rewindpix software**. State clearly and get the
user's explicit consent before guiding any camera-side step:

- This is community-made, unsupported software. Rewindpix is not involved in it.
- Flashing any firmware carries a **risk of rendering the camera unusable ("bricking")**.
- Modifying the firmware **may void the camera's warranty**.
- The authors of OpenFilm6K accept **no liability** for damaged hardware, lost data or lost
  warranty. Everything is done at the user's own risk.
- Risk mitigations: the helper refuses every input it does not recognize; the official updater
  can reinstall the official firmware (§6); and the camera can boot with no card update file
  present (§6).

## 1. What you need

- A Rewindpix PS135 camera and its SD card (FAT32), plus an SD-card reader.
- A computer with internet access and **Python 3** (standard library only; no pip packages).
- The Android phone with the **OpenFilm6K Host** app installed (needed from §5 on) — it comes
  from https://github.com/phaseneko/OpenFilm6K/releases if missing.

## 2. Download the firmware helper (agent)

Latest release asset:

```
https://github.com/phaseneko/OpenFilm6K/releases/latest/download/patch_rewindpix124.py
```

Verify (v0.12.9 copy: 24,753 bytes):

```bash
sha256sum patch_rewindpix124.py
# 3abbde527bc0ae9b8f55dd06e7f5d5fd2952e73e13581f74764f310757c35dc4
```

If the hash differs, a newer release has shipped. Use the copy from the release page and
re-read this guide; do not proceed with an unverifiable helper.

## 3. Build the patched firmware (agent)

Run in the directory holding the helper:

```bash
python3 patch_rewindpix124.py -o FW_X94A_patched.bin
```

The helper fetches the official download page, downloads/caches the official Windows updater,
extracts the official V1.2.4 image, applies the companion patch, and verifies the result.

Expected output:

```
OFFICIAL 1.2.4 OK
patched RTOS OK
output: FW_X94A_patched.bin  size=3306096  sha256=9e2e523b2cb7afa22f68ebd298de053232c8729038c616e8405b0b905d251ddc
matches reference OK
```

Verify independently:

```bash
sha256sum FW_X94A_patched.bin
# 9e2e523b2cb7afa22f68ebd298de053232c8729038c616e8405b0b905d251ddc
```

`--force-download` bypasses the download cache. A local updater ZIP or EXE, an already
extracted official image, or an official download-page URL can also be passed as the input.

**Failure handling.** The helper stops with an `ERROR:` line and writes no output when the
official firmware changed or was modified. Re-download the official updater; if it still
fails, stop and report — the helper and this guide are pinned to the tested V1.2.4 build.

## 4. Install the firmware on the camera (user hands, agent guides)

1. On the card root, remove any other `FW_X94A*` files so exactly one update file remains.
2. Copy the patched file to the **root of the camera's SD card** as `FW_X94A.BIN` (short 8.3
   name, upper case).
3. Insert the card and power the camera on. It installs the file while booting; do not remove
   the card or cut power while the red LED is active.
4. **Success check:** after the camera starts, power it off and read the card. New
   `HFS124.TXT`, `H17_SEND.TXT` / `H17_OK.TXT` / `H17_NONE.TXT` / `H17_SOCK.TXT`, and
   `L_*.TXT` files confirm the new build ran. `HFS42.TXT` / `HFS43.TXT` are old markers and
   must not be used as success checks.

If nothing changes, the file name or card format is wrong. If the camera stays black, follow
§6.

## 5. Configure the Wi-Fi connection (user hands, agent guides)

The camera joins the **phone's hotspot (2.4 GHz)** to talk to the OpenFilm6K Host app.

1. Create `wifi.txt` with exactly two lines — hotspot name, then password (each up to 26
   characters). Use a command so the format is guaranteed (plain text, LF endings, no BOM):

   ```bash
   printf 'MyPhoneHotspot\nthepassword\n' > wifi.txt
   ```

2. Put `wifi.txt` in the card root, reinsert the card, and power the camera on.
3. **Long-press the Wi-Fi key** so it joins the hotspot.
4. On the phone, open OpenFilm6K, tap the 🎞️ **Rewind** button in the title bar, and switch on
   **Rewind sync**. The camera is detected automatically once connected; film slots,
   watermark and post-processing settings are pushed to it, and photos are pulled back the
   same way.

Without `wifi.txt` the camera stays in its own access-point mode; rename or delete the file
and reboot to return to it. If it does not join, recreate `wifi.txt` with the command above,
confirm the hotspot is 2.4 GHz with a password of at most 26 characters, and long-press the
Wi-Fi key again.

## 6. Rollback and emergency recovery (present with §0)

**Rollback to official firmware (camera works):** reinstall the official V1.2.4 firmware with
the official updater tool. Do not redistribute that tool or the official image; the user
obtains it from the vendor. After rollback, delete the `HFS124.TXT` / `H17_*.TXT` markers so
later success checks start clean.

**Emergency (camera stays black / never finishes booting):** power off, remove the card, and
delete every `FW_X94A*` file from the card root. Reinsert the card and power on: with no
update file present the camera boots the firmware already in its flash. From there retry §4
with a correctly built file or reinstall the official firmware as above.

**Build problems:** always a changed or modified official input. Never edit firmware files by
hand.

## 7. Acceptance checklist

- [ ] §0 disclaimer shown and consent given
- [ ] helper hash matched `3abb…`; build printed all four success lines
- [ ] patched file size 3,306,096 and SHA-256 `9e2e…`
- [ ] card root holds `FW_X94A.BIN`; after boot new `HFS124.TXT` and H17 markers appeared
- [ ] `wifi.txt` (two lines) in card root; camera joined the hotspot after a long press
- [ ] OpenFilm6K Rewind sync on; camera shows up and settings were pushed
- [ ] a test shot arrives on the phone and is rendered into the album
