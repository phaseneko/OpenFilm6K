# Film library

- `pipelines/<film>.properties` — one node-graph pipeline per film
  (`<film>.day.properties` / `<film>.night.properties` are the day/night
  variants created by the editor's day/night split).
- `luts/<name>.cube` — 3D LUTs, referenced by the `lut=` line of each
  pipeline; decoupled so several films can share one LUT.

To install, mirror this layout to the phone:

    /sdcard/OpenFilm6K/pipelines/*.properties
    /sdcard/OpenFilm6K/luts/*.cube

All LUTs are original black-box fits; no data from commercial products.
