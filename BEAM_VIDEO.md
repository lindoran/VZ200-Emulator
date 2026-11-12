# Beam-synchronous video (raster effects)

The emulator used to draw the screen from video RAM with one global mode and
to give the CPU a free-running 64 us "horizontal sync" in bit 7 of the $6800
read.  Demos that change the 6847 mode *during* the frame (VZMEGA, the FX*
demos, RASTEST) therefore could not show their effects.  The video is now
driven by the CPU's T-state counter.

Old behaviour: `-Dvz.video.legacy=true` (painter, bit 7 = HS pulse, one IRQ
pulse per 40.96 ms).

## Model

* The 6847 runs from the CPU clock: 1 T-state = 1 6847 clock, a line is 228
  T-states, a frame is `lines` x 228 (312 -> 71,136 T = 50.3 Hz).  Everything
  is counted in T-states, not nanoseconds (the CPU model rounds its period to
  279 ns, which would drift ~90 T per frame).
* FS (6847 pin 37) is wired to /INT and read back as bit 7 of $6800: high
  during the picture, low for `fsLowLines` lines from the end of the active
  display.  The Z80 core now has a real /INT *level* (`Z80.setIRQLine`):
  interrupts are taken for as long as FS is low and IFF1 allows, so a
  handler that ends `EI / RETI` is re-entered until FS rises.  BASIC idles
  with interrupts disabled and is not affected.
* The latch bits that reach the 6847 (AG = bit 3, CSS = bit 4) take effect
  per clock (2 pixels), so a write in the middle of a character cell changes
  the pixels that follow it.  Video RAM is sampled by the beam the same way
  (a store that lands after the beam passed a column shows next frame).
* The 6847 generates its own video RAM addresses with a free-running line
  counter (0..11) and a row address that advances by 32 when a row ends:
  after 12 lines in text / semigraphics, after 3 lines in the VZ's 128x64
  graphics mode.  Both are reset at the top of the active display.  If AG
  changes mid-frame the counters carry on with the other mode's rule, so the
  address fetched on a line depends on the history of the frame.  (This is
  how FXKEFREN's scroller text, stored at graphics row 52, shows up in an
  alpha band at line 157.)
* **Inferred, not from a datasheet:** a change of AG clears the line
  counter.  A kernel that toggles AG on every line therefore never reaches
  the 3-line row end and the 6847 keeps fetching the same row - which is
  what FXKEFREN's "RASTER" bars need (it rewrites one row, 0x70A0, as the
  beam goes by).  Turn it off with `-Dvz.video.agClears=false`.
* Accesses take effect at the end of the executing instruction (its last
  machine cycle); the first byte of a 16 bit store 3 T earlier.
* Colours: text is bright green on dark green (orange for CSS = 1); the
  border is black in text mode and green (CSS = 0) / white (CSS = 1) in
  graphics mode.  The palette was sampled from captures of a real machine
  and is approximate.  Character-ROM glyphs already contain the inverse
  video and block patterns, so a set bit is always "foreground".

## Known differences from the real machine

* Below the active area FXKEFREN's graphics scenes show green/white border
  lines until the bottom of the window; the capture is black there.  Not
  explained yet (candidates: the 6847 blanks while FS is low, or AG is
  sampled differently for the border).
* Data for a column is fetched at display time; the real chip fetches
  slightly earlier.  This only matters for programs that write video RAM a
  few clocks ahead of the beam.

## Constants that documentation does not settle (all overridable)

`-Dvz.video.NAME=value`

| name          | default | meaning |
|---------------|---------|---------|
| lines         | 312     | lines per frame (262 for a 60 Hz machine) |
| fsLowLines    | 57      | lines FS stays low (MAME/datasheet layout; **RASTEST prints the real value as "FIELD SYNC LOW LINES"**) |
| fsEdge        | 212     | clock inside the line at which FS changes |
| activeClock   | 25      | clock inside the line of the first active column (MAME's empirical value; the sunset scene is centred like the capture) |
| latchOffset   | 0       | T-states added to a latch write's effective time |
| readOffset    | 0       | T-states added to an FS read's effective time |

`latchOffset` moves the whole raster picture sideways by 2 pixels per T.  With the defaults the VZMEGA logo is centred in the 320 pixel
window.

## Calibrating against real hardware

Run RASTEST.VZ on the real machine.  Put "SCANLINES PER FIELD" into `lines`
and "FIELD SYNC LOW LINES" into `fsLowLines`.  Then compare the VZMEGA logo's
horizontal position with the emulator and trim `latchOffset` / `activeClock`.
None of these three can be confirmed without the hardware.

## Tests (headless)

    ./build.sh
    javac -cp build:jar/z80.jar -d test/build test/emulator/vz200/*.java
    java -Djava.awt.headless=true -cp build:test/build:jar/z80.jar \
         emulator.vz200.BeamTimingTest [--quick]

`BeamDemoTest NAME OUTDIR [FRAMES]` boots, LOADs NAME from ./SD and writes
PNG snapshots; `GuiSmoke` does the same through the real Swing classes (needs
a display, e.g. `xvfb-run`).
