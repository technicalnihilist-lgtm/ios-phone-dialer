#!/bin/bash
set -e
SDK=${SDK:-/scratch/work/sdk}
# Adoptium names the unpacked directory after the exact build it shipped, so a
# hard-coded "jdk-17.0.21+9" stopped resolving the day 17.0.22 came out. Take
# whatever jdk-* is actually there.
if [ -z "$JAVA_HOME" ]; then
  JAVA_HOME=$(ls -d "$SDK"/jdk-* 2>/dev/null | head -1 || true)
fi
export JAVA_HOME
if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
  echo "!! no JDK found under $SDK (expected $SDK/jdk-*)"
  exit 1
fi
export PATH=$JAVA_HOME/bin:$SDK/android-14:$PATH
PLATFORM=$SDK/android-35/android.jar
BT=$SDK/android-14
J="$JAVA_HOME/bin/java -Xmx192m -XX:MaxMetaspaceSize=64m -XX:CompressedClassSpaceSize=32m -XX:ReservedCodeCacheSize=16m -XX:-TieredCompilation -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss512k -XX:-UsePerfData -XX:MaxDirectMemorySize=64m"
# work from the directory this script lives in: the checkout can be cloned
# anywhere, and a hard-coded path only ever built the one layout
cd "$(dirname "$0")"

# assets/index.html is the page aapt2 puts in the APK, and nothing used to keep
# it in step with the page in the repository root: a forgotten copy shipped the
# previous build's UI inside a new APK. Copy it in as the first thing we do.
if [ -f ../index.html ]; then
  cp ../index.html assets/index.html
else
  echo "!! ../index.html missing: packaging whatever assets/ already holds"
fi

rm -rf build && mkdir -p build/gen build/classes build/dex

echo "== aapt2 compile"
aapt2 compile --dir res -o build/res.zip

echo "== aapt2 link"
aapt2 link -o build/base.apk \
  -I "$PLATFORM" \
  --manifest AndroidManifest.xml \
  -R build/res.zip \
  -A assets \
  --java build/gen \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  --auto-add-overlay

echo "== javac"
javac -J-Xmx224m -J-XX:MaxMetaspaceSize=96m -J-XX:CompressedClassSpaceSize=48m -J-XX:ActiveProcessorCount=1 -J-Xss512k -J-XX:+UseSerialGC -J-XX:-TieredCompilation \
  -source 8 -target 8 -nowarn -encoding UTF-8 \
  -bootclasspath "$PLATFORM" -classpath "$PLATFORM" \
  -d build/classes \
  $(find src -name '*.java') \
  build/gen/com/phone/dialer/R.java

echo "== d8"
$J -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --lib "$PLATFORM" --min-api 24 --output build/dex \
  $(find build/classes -name '*.class')

echo "== package"
cp build/base.apk build/unsigned.apk
( cd build && zip -q -j unsigned.apk dex/classes.dex )

echo "== zipalign"
zipalign -f -p 4 build/unsigned.apk build/aligned.apk

echo "== keystore"
# Never mint a key here. An APK signed with a freshly generated key has a
# different signature, so the system refuses to install it over the build that
# is already on the device. The release key lives outside this repo: point
# KEYSTORE at it. Without one the build stops at an unsigned, aligned APK.
KS=${KEYSTORE:-phone.keystore}
if [ ! -f "$KS" ]; then
  echo "!! no signing key at $KS"
  echo "!! APK left unsigned at build/aligned.apk - do not publish it"
  exit 0
fi

echo "== sign"
$J -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KS" --ks-pass "pass:${KS_PASS:-phonedialer}" --key-pass "pass:${KEY_PASS:-phonedialer}" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out Phone-Dialer.apk build/aligned.apk

echo "== verify"
$J -jar "$BT/lib/apksigner.jar" verify --print-certs Phone-Dialer.apk | head -5
ls -la Phone-Dialer.apk
