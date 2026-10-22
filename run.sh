#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

usage() {
  echo "Usage: $0 {stock|sd-loader} [emulator options...]" >&2
  exit 2
}

[ "$#" -gt 0 ] || usage
mode=$1
shift

case "$mode" in
  stock)
    set -- --os-rom "$ROOT/BIOS/VZ200_20.bin" "$@"
    ;;
  sd-loader)
    set -- --os-rom "$ROOT/BIOS/VZ200_20.bin" \
      --dos-rom "$ROOT/SD/VZDOS.VZ" --sd-dir "$ROOT/SD" "$@"
    ;;
  *)
    usage
    ;;
esac

if [ ! -f "$ROOT/jar/vz200.jar" ] || [ ! -f "$ROOT/jar/z80.jar" ]; then
  echo "Emulator jars not found; build them first with $ROOT/build.sh" >&2
  exit 1
fi

exec java -jar "$ROOT/jar/vz200.jar" "$@"
