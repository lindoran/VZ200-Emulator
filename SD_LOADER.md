# Benn Venn SD Loader support

Register-level model of the Benn Venn SD Loader (Mk I/II) for the Java VZ200
emulator.  It is not a logic-level model and not cycle accurate: it makes the
loader's ports and memory respond the way the real DOS firmware (VZDOS) and
Benn Venn titles expect, so the *unmodified* VZDOS firmware runs on top of it.

## Running

    java -jar jar/vz200.jar \
         --os-rom  refrence_rom.bin \      # 16 KB BASIC ROM (or build it in as os.rom)
         --dos-rom VZDOS.VZ \              # loader firmware (VZDOS.VZ or VZDOS.BIN/.OBJ)
         --sd-dir  path/to/folder          # or: --sd-image card.img

Other options: `--sd-ro` (never modify the card/folder), `--sd-trace` (print
every SD command and loader register write), `--sd-mbr` (see below),
`--sd-loader` (loader hardware with no card).  Any `--dos-rom`/`--sd-*` option
enables the loader.  With none of them the emulator is a plain VZ200 as before.

At the DOS prompt `DIR`, `LOAD"NAME"`, `SAVE"NAME"`, `CD"DIR"` behave as on the
real loader because the real firmware is executing.

### Card backends

* `--sd-image FILE`  raw card image (a `dd` dump; MBR or no partition table).
  Writes go straight into the file (or are discarded with `--sd-ro`).
* `--sd-dir DIR`  the folder is presented as a fresh FAT32 card (subfolders
  included).  Only valid 8.3 names are exported (others are skipped with a
  warning).  When the guest writes, new and changed files are written back to
  the folder after ~0.4 s and at exit; files are never deleted.
  The synthesized volume can be saved as an image with
  `FolderBlockDevice.saveImage()` (used by the tests).

## What is modelled

| Port (dec/hex) | Behaviour |
|---|---|
| 55 / 0x37 | memory control: bit0 EPROM(0)/SRAM(1) in the DOS area, bit1 DOS SRAM bank, bit2 high SRAM bank, bit3 LED |
| 56 / 0x38 | SPI config: bit1 = card chip-select asserted |
| 57 / 0x39 | SPI data: write exchanges one byte, read returns the byte shifted in |
| 58 / 0x3A | RAM map: 0 = loader SRAM from $9000 (VZ200), 1 = from $B800 (VZ300) |
| 74 / 0x4A | SNES pad enable: stored only |

**The manual's port table has 55 and 58 the other way round** (55 = RAM map,
58 = bank).  VZDOS itself and the Elite/Mercenary ports use them as above:
VZDOS tries port 58 = 1 and falls back to 0 if there is no RAM at $9100
(VZ300 / VZ200 detection), and its self-update stub writes port 55 = 1 before
copying itself into $4000.

Memory: $4000-$67FF DOS area (EPROM $4000-$5FFF, 2 SRAM banks; writes always go
to SRAM), loader SRAM from $9000 or $B800 (per port 58) to $FFFF in 2 banks.
Use `--vz300-ram` to give the machine 18 KB of its own RAM ($7800-$B7FF) like a
VZ300; without it the machine has 6 KB and VZDOS detects VZ200 mode.
The SD card is an SPI-mode SDHC card: CMD0, 8, 12, 13, 16, 17, 24, 55, 58,
ACMD41 (what VZDOS uses).

Not modelled: SNES pad ports 37-46/64-73, SPI timing, CRCs, multi-block
commands, anything not used by VZDOS 2.32.

## Things that could NOT be confirmed without real hardware

1. **Port roles.**  Taken from what VZDOS and the Elite/Mercenary ports do on
   real machines, not from the manual (which disagrees).  Bit assignments inside
   port 55 are from the manual's description of the register.
2. **Read-back of ports 55/56/58/74.**  Nothing in VZDOS reads them; the model
   returns $FF (undriven bus).
3. **Bit 0 of port 56.**  VZDOS only ever writes 1 and 3; only bit1 is used.
4. **SRAM extents**: EPROM above $5FFF reads $FF while the EPROM is selected;
   high SRAM size and bank layout come from the manual's wording.
5. **Where the real load limit is** (see below): the emulator cannot reproduce
   the failure reported at about $F4B0 on real cards.

## Finding: VZDOS 2.32 needs a card without a partition table to follow FAT chains

VZDOS adds the partition start when it reads the root directory and file data,
but its FAT lookup (`(cluster >> 7) + reserved sectors`, around $4930) does not.
Files that fit in one cluster never need a FAT lookup, so they work on any
layout; a file that spans clusters (VZDOS.VZ itself, big .DAT files) only loads
on a card where the FAT32 boot sector is sector 0, i.e. no MBR.  This matches
what a Windows-formatted, sector-0-wiped card looks like (see the manual).
Therefore `--sd-dir` builds a card with **no partition table** by default;
`--sd-mbr` gives the partitioned layout so you can reproduce the limitation.
If you use `--sd-image`, make the image the same way.  This is observed from
the firmware's behaviour in the emulator, not from hardware.

## Tests

`test/` holds a headless harness (real Z80 core, real BASIC ROM and VZDOS, no
GUI):

    javac -d /tmp/tb -cp build:jar/z80.jar test/emulator/vz200/*.java
    java  -cp build:/tmp/tb:jar/z80.jar emulator.vz200.SDLoaderTest \
          BASIC_ROM.bin DIR_WITH_VZDOS.VZ_WT.VZ_FILE1.DAT WORKDIR
    java  -cp build:/tmp/tb:jar/z80.jar emulator.vz200.ImageBootTest \
          BASIC_ROM.bin VZDOS.VZ card.img

It checks the register/banking behaviour, the SD protocol, and boots VZDOS
against folder cards, then runs `DIR` and `LOAD"WT"` (the File IO test) and
checks FILE1.DAT is written back.

## Elite VZ (stage 6) results

Loaded through the real VZDOS `LOAD"NAME"` from a folder card and from
`mkfs.fat` images with 4 KB, 16 KB and 32 KB clusters (VZ300 RAM map):

* `ELITE.VZ` (7AE9-F351): RAM identical to the file when VZDOS jumps to it; the
  game unpacks (about 2,250 frames, the "45 seconds") and draws its cockpit panel.
* `SDT29500` ... `SDT32000` (ending at up to $F7D0): all load and report OK.
* `SDT33000`, `SDT34000` (ending at $FBB8 / $FFA0): VZDOS never starts them.
  The file overwrites the DOS workspace from $F800 (sector buffer and
  variables), which supports the README's explanation of the mechanism.

The emulator's cut-off is therefore about $F800, not the roughly $F4B0 the
README infers from user reports, and it does not depend on cluster size.  So
something that differs on the real card (other VZDOS version, other
workspace use, card behaviour) is not reproduced, and the emulator alone cannot
confirm that Elite loads from a real card.
