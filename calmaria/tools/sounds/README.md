# Recording pipeline

How the bundled recordings in `app/src/main/assets/sounds` were made. Requires Python 3 with
`numpy` and `scipy`, plus `ffmpeg` (with libvorbis and soxr).

- `make_loop.py` turns a segment of a recording into a seamless loop: it searches near the requested
  segment for the loop length and crossfade position where the head and tail match best (loudness,
  spectrum, no transients in the overlap), builds an equal-power crossfade, high-passes, normalizes to a
  target loudness (EBU R128, with up to 6 dB of transparent look-ahead limiting on peaks), encodes 48 kHz
  Ogg Vorbis and verifies the decoded length and the seam. Short, steady recordings can be extended by
  re-stitching random chunks so they don't repeat every few seconds.
- `polish_recording.py` cleans up a short phone recording (trim, EQ against road/handling rumble) and
  re-stitches it into a long, wide stereo loop: the right channel is the same loop half a turn later,
  checked to never play the same moment of the recording as the left one. Used for “Chuva na estrada”.
- `audio_analysis.py` has the decoding and measurement helpers (and the CLAP scoring used to check that
  candidates sound like what they should, contain no speech/music, etc.).
- `recordings.json` records, for every bundled sound, the source page and file, the exact segment and
  the settings used. Credits are in `../../CREDITS.md` and `app/src/main/assets/sounds/credits.json`.

Example:

```sh
python3 -c 'import make_loop, json; print(json.dumps(make_loop.make(
    src="rain.flac", start=280, length=60, region=[280, 350], xfade=4, target_lufs=-18,
    out_path="rain_medium.ogg", tags={"title": "…", "artist": "…"})))'
```

Only use recordings whose license allows redistribution and modification (CC0, public domain, CC BY,
CC BY-SA) and add them to the credits.
