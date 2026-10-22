#!/bin/sh
# Builds jar/z80.jar and jar/vz200.jar from source, including the
# resource files (icons, ROM font, annotation files and schema) that
# the programs load from inside the jars.  Needs a JDK (javac, jar).
set -e
cd "$(dirname "$0")"
rm -rf build/emulator
mkdir -p build jar

# z80 library
( cd src/z80/java && javac -nowarn -d ../../../build emulator/z80/*.java )
cp src/z80/java/emulator/z80/*.xsd build/emulator/z80/
( cd build && jar cfm ../jar/z80.jar ../src/z80/MANIFEST.MF emulator/z80 )

# VZ200 emulator
( cd src/vz200/java && javac -nowarn -classpath ../../../jar/z80.jar \
    -d ../../../build emulator/vz200/*.java )
cp src/vz200/java/emulator/vz200/*.rom \
   src/vz200/java/emulator/vz200/*.png \
   src/vz200/java/emulator/vz200/annotations*.xml build/emulator/vz200/
( cd build && jar cfm ../jar/vz200.jar ../src/vz200/MANIFEST.MF emulator/vz200 )

echo "built jar/z80.jar and jar/vz200.jar"
unzip -l jar/vz200.jar | grep -c '\.png' | sed 's/^/icons in vz200.jar: /'
unzip -l jar/z80.jar   | grep -c '\.xsd' | sed 's/^/schema in z80.jar: /'
