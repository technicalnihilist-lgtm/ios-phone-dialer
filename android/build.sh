#!/bin/bash
set -e
SDK=/scratch/work/sdk
export JAVA_HOME=$SDK/jdk-17.0.21+9
export PATH=$JAVA_HOME/bin:$SDK/android-14:$PATH
PLATFORM=$SDK/android-35/android.jar
BT=$SDK/android-14
J="$JAVA_HOME/bin/java -Xmx192m -XX:MaxMetaspaceSize=64m -XX:CompressedClassSpaceSize=32m -XX:ReservedCodeCacheSize=16m -XX:-TieredCompilation -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss512k -XX:-UsePerfData -XX:MaxDirectMemorySize=64m"
cd /scratch/work/android

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
if [ ! -f phone.keystore ]; then
  keytool -J-Xmx192m -J-XX:CompressedClassSpaceSize=32m -J-XX:ActiveProcessorCount=1 -J-XX:+UseSerialGC \
    -genkeypair -keystore phone.keystore -alias phone \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass phonedialer -keypass phonedialer \
    -dname "CN=Phone Dialer, OU=Mobile, O=Phone, L=Mumbai, ST=Maharashtra, C=IN" 2>&1 | tail -1
fi

echo "== sign"
$J -jar "$BT/lib/apksigner.jar" sign \
  --ks phone.keystore --ks-pass pass:phonedialer --key-pass pass:phonedialer \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out Phone-Dialer.apk build/aligned.apk

echo "== verify"
$J -jar "$BT/lib/apksigner.jar" verify --print-certs Phone-Dialer.apk | head -5
ls -la Phone-Dialer.apk
