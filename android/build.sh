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

# A signed APK from an earlier run used to survive a build that had no key to
# sign with: the no-keystore path below exits 0 and leaves Phone-Dialer.apk
# exactly where it was, so the directory could hold a freshly built tree next to
# a stale, perfectly publishable APK. Drop it before we start — only the sign
# step near the end of this script puts it back.
rm -f Phone-Dialer.apk

# assets/index.html is the page aapt2 puts in the APK, and nothing used to keep
# it in step with the page in the repository root: a forgotten copy shipped the
# previous build's UI inside a new APK. Copy it in as the first thing we do.
#
# A missing ../index.html used to be a warning, and the build then went on to
# package whatever assets/ happened to hold — which is the exact defect this
# copy exists to prevent, only quieter: the checkout could ship a stale page,
# or no page at all, under a new version number, and the parity check further
# down was skipped for the same reason so nothing failed. There is no page to
# build without it, so stop here instead.
if [ ! -f ../index.html ]; then
  echo "!! ../index.html missing: this is the page the APK ships, nothing to build"
  exit 1
fi
cp ../index.html assets/index.html

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
# d8 writes classes.dex and, once the app outgrows one dex, classes2.dex and so
# on. Only classes.dex was ever packaged, so the day that happened the build
# would have shipped an APK that was missing every method in the later dex
# files — a NoClassDefFoundError at runtime with nothing to show it went wrong.
( cd build && zip -q -j unsigned.apk dex/*.dex )

# The runbook asks for this by hand after every build, because an index.html that
# did not land in the APK ships the previous build's UI under a new version
# number — the exact defect the copy at the top of this script was added for.
# Take the check here, where it cannot be forgotten: the asset the APK actually
# carries has to be byte for byte the page in the repository root.
WANT=$(md5sum ../index.html | cut -d' ' -f1)
GOT=$(unzip -p build/unsigned.apk assets/index.html | md5sum | cut -d' ' -f1)
if [ "$WANT" != "$GOT" ]; then
  echo "!! assets/index.html in the APK ($GOT) is not ../index.html ($WANT)"
  exit 1
fi
echo "   packaged assets/index.html matches ../index.html ($WANT)"

echo "== zipalign"
zipalign -f -p 4 build/unsigned.apk build/aligned.apk

echo "== keystore"
# Never mint a key here. An APK signed with a freshly generated key has a
# different signature, so the system refuses to install it over the build that
# is already on the device. The release key lives outside this repo: point
# KEYSTORE at it. Without one the build stops at an unsigned, aligned APK.
KS=${KEYSTORE:-phone.keystore}
# ...and the runbook names where that key is kept: /scratch/saved/phone-keystore.jks,
# with the alias and the two passwords this script already defaults to. Nothing
# ever looked there, so a run that followed AUTOMATION.md exactly still had to
# remember to pass KEYSTORE=... on the command line — and one that did not built
# unsigned, skipped the release and reported the key as unavailable while the
# key was sitting on disk the whole time. Fall back to the documented path when
# no key was named and none sits beside this script. The check below is
# unchanged, so a machine with no key anywhere still stops at the unsigned APK.
if [ ! -f "$KS" ] && [ -z "$KEYSTORE" ] && [ -f /scratch/saved/phone-keystore.jks ]; then
  KS=/scratch/saved/phone-keystore.jks
  echo "   using the runbook's signing key at $KS"
fi
if [ ! -f "$KS" ]; then
  echo "!! no signing key at $KS"
  echo "!! APK left unsigned at build/aligned.apk - do not publish it"
  exit 0
fi

# The alias was never named here, so apksigner signed with whichever key it
# found first in the store. With a one-key keystore that is the same key and
# nothing shows; the moment the store holds a second entry the build signs with
# a different key, and an APK whose signature changed will not install over the
# build already on the device. The release key's alias is fixed: name it.
echo "== sign"
$J -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KS" --ks-key-alias "${KS_ALIAS:-phone}" \
  --ks-pass "pass:${KS_PASS:-phonedialer}" --key-pass "pass:${KEY_PASS:-phonedialer}" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out Phone-Dialer.apk build/aligned.apk

# The parity check above ran on build/unsigned.apk, which is not what ships:
# zipalign rewrites the archive and apksigner rewrites it again. Check the file
# that actually leaves this script, so the invariant the runbook asks for by
# hand — the APK's assets/index.html is byte for byte the page in the
# repository root — is answered for the APK itself and cannot be skipped.
SHIPPED=$(unzip -p Phone-Dialer.apk assets/index.html | md5sum | cut -d' ' -f1)
if [ "$WANT" != "$SHIPPED" ]; then
  echo "!! Phone-Dialer.apk carries assets/index.html $SHIPPED, not ../index.html $WANT"
  rm -f Phone-Dialer.apk
  exit 1
fi
echo "   Phone-Dialer.apk carries ../index.html ($WANT)"

echo "== verify"
# `apksigner verify ... | head -5` reports the exit status of head, so under
# `set -e` an APK that failed verification — a bad signature, a tampered
# archive — was announced as a successful build. Keep the output, then decide.
VERIFY=$($J -jar "$BT/lib/apksigner.jar" verify --print-certs Phone-Dialer.apk) || {
  echo "!! apksigner verify failed:"
  echo "$VERIFY"
  exit 1
}
echo "$VERIFY" | head -5
ls -la Phone-Dialer.apk
