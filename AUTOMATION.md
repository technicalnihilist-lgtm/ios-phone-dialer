# Automation runbook

This repository is improved by a scheduled agent run. Read this file before changing anything.

## The one hard rule

**The UI must not change.** Layout, spacing, colours, typography, icons, shadows, animation *look*, screen order and feature set stay exactly as they are. A user opening two consecutive builds must not be able to tell them apart by looking.

Allowed: bug fixes, glitch fixes, crash fixes, correctness, performance, fluidity, memory and battery use, input handling, accessibility under the hood, defensive coding, dead-code removal, build hygiene.

Not allowed: new buttons, new screens, new settings, restyled components, moved elements, renamed labels, new dependencies, framework changes.

## Each run

1. Clone the repo (it is public, no auth needed):

   ```
   git clone --depth 1 https://github.com/technicalnihilist-lgtm/ios-phone-dialer.git
   ```

2. Read `index.html` (the whole app UI) and `android/src/com/phone/dialer/*.java`.

3. Find and make up to **10 real, verifiable improvements**. Quality beats count: if only four genuine issues exist, fix four and say so. Never invent a bug to fill the quota, and never "fix" something that is not broken.

4. Verify before publishing:
   - extract the inline script and run `node --check` on it;
   - rebuild the APK and confirm `unzip -p Phone-Dialer.apk assets/index.html | md5sum` equals `md5sum android/assets/index.html`;
   - check the version with `aapt dump badging`.

5. Bump `versionCode` (+1) and `versionName` (patch or minor) in `android/AndroidManifest.xml`, and the version rows in `README.md`.

6. Publish. `git push` does **not** work here (the GitHub App is not installed on this repo and returns 403), so commit through the API instead:
   - `GITHUB_CREATE_A_BLOB` for every binary file (base64), `content` inline for text files;
   - `GITHUB_CREATE_A_TREE` (no `base_tree`, include every file, `100755` for `*.sh`);
   - `GITHUB_CREATE_A_COMMIT` with `parents` = the current head of `main` (read it from the API, never assume);
   - `GITHUB_UPDATE_A_REFERENCE` on `heads/main`.
   - Then `GITHUB_CREATE_A_RELEASE` (new tag) plus `upload` → `GITHUB_UPLOAD_RELEASE_ASSET` for the APK.

7. Report in plain language: what was broken, what changed, what was verified.

## Building the APK

The sandbox has no Java or Android SDK; fetch them each run.

```
curl -sL https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse -o jdk.tar.gz
curl -sL https://dl.google.com/android/repository/build-tools_r34-linux.zip -o bt.zip     # unpacks to android-14
curl -sL https://dl.google.com/android/repository/platform-35_r01.zip  -o plat.zip        # android.jar
```

The container caps memory hard, and the default JVM flags fail. Always pass:

```
-Xmx192m -XX:MaxMetaspaceSize=64m -XX:CompressedClassSpaceSize=32m -XX:ReservedCodeCacheSize=16m
-XX:-TieredCompilation -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss512k -XX:-UsePerfData
```

`javac` needs `-source 8 -target 8 -encoding UTF-8 -bootclasspath android.jar`. Because `android.jar` is the bootclasspath, **lambdas do not compile** (no `LambdaMetafactory`) — use anonymous inner classes.

`android/build.sh` runs the whole chain: `aapt2 compile` → `aapt2 link` → `javac` → `d8` → zip → `zipalign` → `apksigner`. Point its `SDK=` variable at wherever you unpacked the tools.

### Signing

Updates only install over the existing app if they carry the **same** signature, so the keystore is kept outside this public repo.

- Look for `/scratch/saved/phone-keystore.jks`.
- Alias `phone`, store password `phonedialer`, key password `phonedialer`.
- **If the keystore is not there, do not generate a new one.** Build unsigned, skip the release, push the source changes only, and say in the report that the APK was not rebuilt because the signing key was unavailable.

## Notes

- Contacts, call history and voicemail come from the device. There is deliberately no sample data; empty states are correct behaviour, not a bug.
- Call recording is blocked by Android 9+ for third-party apps. The app says so instead of pretending. Do not "fix" this.
- Speaker, mute and hold need the default-dialer role. That is by design.
