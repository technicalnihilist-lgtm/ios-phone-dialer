<div align="center">

<a href="https://github.com/technicalnihilist-lgtm/ios-phone-dialer/stargazers"><img src="https://img.shields.io/badge/stars-2-5A31F4?style=for-the-badge&logo=github&logoColor=white" alt="stars: 2"></a>&nbsp;<a href="https://github.com/technicalnihilist-lgtm/ios-phone-dialer/network/members"><img src="https://img.shields.io/badge/forks-0-3FB950?style=for-the-badge" alt="forks: 0"></a>

<h3>A pixel-faithful iOS Phone app for Android — real contacts, real call log, real calls, and a native…</h3>

<img src="https://img.shields.io/badge/HTML-5865F2?style=flat-square" alt="HTML"> <img src="https://img.shields.io/badge/Android-3B3F4A?style=flat-square" alt="Android"> <img src="https://img.shields.io/badge/APK-3FB950?style=flat-square" alt="APK"> <img src="https://img.shields.io/badge/Dialer-EC4899?style=flat-square" alt="Dialer">

<a href="https://github.com/technicalnihilist-lgtm/ios-phone-dialer/releases/latest"><img src="https://img.shields.io/badge/DOWNLOAD-APK-6D5AE6?style=for-the-badge&logo=android&logoColor=white" alt="Download APK"></a> <a href="https://technicalnihilist-lgtm.github.io/ios-phone-dialer/"><img src="https://img.shields.io/badge/OPEN-WEBSITE-EC4899?style=for-the-badge&logo=googlechrome&logoColor=white" alt="Website"></a>

<b>Support the work</b>

<img src="https://img.shields.io/badge/Buy%20me%20a%20coffee-COMING%20SOON-4B5563?style=for-the-badge&logo=buymeacoffee&logoColor=white" alt="Buy me a coffee — coming soon"> <img src="https://img.shields.io/badge/UPI-COMING%20SOON-4B5563?style=for-the-badge&logo=googlepay&logoColor=white" alt="UPI — coming soon"> <img src="https://img.shields.io/badge/PayPal-COMING%20SOON-4B5563?style=for-the-badge&logo=paypal&logoColor=white" alt="PayPal — coming soon">

<sub>Donations aren't live yet — Paytm, UPI, PayPal and Buy&nbsp;Me&nbsp;a&nbsp;Coffee all show <b>coming soon</b>. Until then, a ⭐ star is the best support.</sub>

<a href="https://github.com/technicalnihilist-lgtm/ios-phone-dialer/releases/latest"><img src="https://img.shields.io/badge/VERSION-v2.3-6D5AE6?style=for-the-badge" alt="VERSION: v2.3"></a> <img src="https://img.shields.io/badge/DOWNLOADS-1.2K-3FB950?style=for-the-badge" alt="DOWNLOADS: 1.2K"> <a href="https://technicalnihilist-lgtm.github.io/ios-phone-dialer/"><img src="https://img.shields.io/badge/WEBSITE-LIVE-EC4899?style=for-the-badge&logo=googlechrome&logoColor=white" alt="WEBSITE: LIVE"></a>

<h4>Get it from</h4>

<a href="https://github.com/technicalnihilist-lgtm/ios-phone-dialer/releases"><img src="https://img.shields.io/badge/GitHub-Releases-181717?style=for-the-badge&logo=github&logoColor=white" alt="GitHub Releases"></a> <a href="https://technicalnihilist-lgtm.github.io/ios-phone-dialer/"><img src="https://img.shields.io/badge/Website-Live-EC4899?style=for-the-badge&logo=googlechrome&logoColor=white" alt="Website"></a> <a href="https://github.com/technicalnihilist-lgtm/ios-phone-dialer/archive/refs/heads/main.zip"><img src="https://img.shields.io/badge/Source-ZIP-0EA5E9?style=for-the-badge&logo=github&logoColor=white" alt="Source ZIP"></a>

</div>

---

---

## Install

**Android (APK)** — grab it from the [latest release](https://github.com/technicalnihilist-lgtm/ios-phone-dialer/releases/latest) (or [`apk/Phone-Dialer.apk`](apk/Phone-Dialer.apk) in the repo), open it on the phone, and allow install from unknown sources when prompted. Android 7.0+ (API 24). Adaptive icon on Android 8+.

On first launch it asks for contacts, call log and phone permissions. The microphone is only requested when you tap **Record** during a call. To use the in-call controls, tap **Set** on the banner and make Phone your default dialer.

**iPhone / Android (web app)** — open <https://technicalnihilist-lgtm.github.io/ios-phone-dialer/> in Safari or Chrome, then *Share → Add to Home Screen*. It launches full screen with its own icon and works offline.

**Desktop** — just open `index.html`.

## What works

| Feature | Android app | Browser |
| --- | --- | --- |
| Real contacts from the device | yes (`READ_CONTACTS`) | empty state — no sample data |
| Real call history | yes (`READ_CALL_LOG`) | empty state — no sample data |
| Places real calls | yes (`CALL_PHONE`) | hands off to `tel:` |
| Speaker / mute / hold | yes, with the default-dialer role | simulated |
| DTMF keypad mid-call | yes | simulated |
| Call recording | attempted — see below | no |
| Offline | yes | yes (service worker) |

### About recording

Android 9 removed `VOICE_CALL` audio capture for ordinary apps, and Android 10+ blocks it outright. The record button tries `MediaRecorder` with the `VOICE_CALL` source; on devices that still permit it (some OEM builds) it writes an `.m4a` to the app's external files folder. Everywhere else the OS refuses and the app tells you so rather than pretending to record. No third-party dialer can bypass this — it needs root or a system signature.

### About the default-dialer role

Speaker, mute, hold and DTMF only work on a live call when the app owns the call, which means holding the dialer role (`InCallService`). Without the role the app still dials, but Android's own in-call screen takes over and these controls live there instead. The banner in the app requests the role.

## Keypad details

Real iOS metrics: 75pt keys, letters under each digit, T9 contact matching as you type (2-8-2 finds Ava), long-press `0` for `+`, long-press backspace to clear, long-press the empty area above the keypad for **Paste**. Five-tab Classic layout — Favorites, Recents, Contacts, Keypad, Voicemail — in a floating Liquid Glass tab bar whose selection capsule slides, stretches and can be dragged with a finger. Light and dark follow the system appearance, exactly as the real app does.

## Build the APK

Requires a JDK and Android build-tools. `android/build.sh` does everything: `aapt2` compile and link, `javac`, `d8`, zip, `zipalign`, then `apksigner` with an existing keystore. The script never mints a key: an APK signed with a fresh one would not install over the build already on the device. Point `KEYSTORE` at your key, or the build stops at an unsigned, aligned APK.

```bash
cd android
SDK=/path/to/android-sdk
KEYSTORE=/path/to/phone.keystore bash build.sh   # -> android/Phone-Dialer.apk
```

Unpack the three downloads the runbook names into `$SDK`: the Adoptium JDK (it unpacks to a
`jdk-17.0.x+y` directory, not `jdk`), `build-tools_r34-linux.zip` (unpacks to `android-14`) and
`platform-35_r01.zip` (unpacks to `android-35`, and provides `android.jar`). The script finds the
`jdk-*` directory itself and puts the JDK and build-tools on `PATH` for you — leave `JAVA_HOME`
unset, or point it at the directory that was actually unpacked. Setting it to a path that does not
exist makes the script stop with `no JDK found`, because it only searches when `JAVA_HOME` is empty.

Without `KEYSTORE` the script builds and aligns the APK and then stops, leaving an unsigned
`build/aligned.apk` and no `Phone-Dialer.apk`: never mint a key here, because an APK signed with a
fresh one will not install over the build already on the device.

## Project layout

```
index.html               the whole UI — one self-contained file
manifest.webmanifest     PWA manifest
sw.js                    service worker (offline cache)
icons/                   app icons (180 / 192 / 512)
screenshots/             rendered from index.html
apk/Phone-Dialer.apk     signed, installable build (v2.3 — see note below)
android/                 native app source
  AndroidManifest.xml    permissions, dialer role, InCallService
  src/com/phone/dialer/
    MainActivity.java    WebView shell + contacts/call-log/dial bridge
    DialerInCallService.java   owns the live call
    CallActivity.java    in-call screen: speaker, mute, hold, keypad, record
    DialerActivity.java  role-eligibility trampoline
  res/                   theme, styles, in-call layout and icons
  assets/index.html      the same UI, packaged
  build.sh               full toolchain build
```

## Notes

- The APK is signed with a locally generated key (`CN=Phone Dialer`), fine for sideloading but not a Play Store key.
- The bundled `apk/Phone-Dialer.apk` is the 2.3 build (versionCode 6): the source is at 2.6.88 (versionCode 115) but the signing
  key was not available for this release, and an APK signed with a fresh key would not install over the existing one.
  `android/build.sh` now reads that APK and prints the drift, so the two numbers cannot silently disagree again.
- Typography is only pixel-identical on Apple hardware, where SF Pro resolves. Elsewhere it falls back to Helvetica Neue / the system sans.
- There is no sample data anywhere: contacts, call history and voicemail come from the device, so the browser build shows empty lists until the Android app fills them in.

## Store metadata

| Field | Value |
| --- | --- |
| App name | Phone |
| Package name / App ID | `com.phone.dialer` |
| Category | Utility |
| Version | 2.6.88 (versionCode 115) |
| Minimum Android | 7.0 (API 24) |
| Price | Free |
| Icon | https://raw.githubusercontent.com/technicalnihilist-lgtm/ios-phone-dialer/main/icons/icon-512.png |
| License | MIT |

---

---

## 🌍 Languages

<sub>Donations are <b>coming soon</b> in every language below — nothing to pay today, nothing to click.</sub>

<div align="center">

<sub><b>हिन्दी · বাংলা · मराठी · తెలుగు · தமிழ் · ગુજરાતી · ಕನ್ನಡ · മലയാളം · ਪੰਜਾਬੀ · ଓଡ଼ିଆ · অসমীয়া · اردو</b></sub>

</div>

<details>
<summary><b>🌍 Open the same message in 53 languages</b></summary>

**🇮🇳 Indian languages**

- **हिन्दी** (Hindi) — ☕ जल्द ही आ रहा है। · ⭐ अगर आपको यह ऐप और हमारा काम पसंद है, तो कृपया रिपॉजिटरी को स्टार दें और हमें फॉलो करें।
- **বাংলা** (Bengali) — ☕ খুব শিগগিরই আসছে। · ⭐ যদি আপনার এই অ্যাপ এবং আমাদের কাজ ভালো লাগে, তবে অনুগ্রহ করে রিপোজিটরিটিকে একটি স্টার দিন এবং আমাদের ফলো করুন।
- **मराठी** (Marathi) — ☕ लवकरच येत आहे. · ⭐ जर तुम्हाला हे ॲप आणि आमचं काम आवडलं असेल, तर कृपया रिपॉझिटरीला स्टार द्या आणि आम्हाला फॉलो करा.
- **తెలుగు** (Telugu) — ☕ త్వరలో రాబోతోంది. · ⭐ మీకు ఈ యాప్, మా పని నచ్చితే, దయచేసి రిపోజిటరీకి ఒక స్టార్ ఇచ్చి మమ్మల్ని ఫాలో అవ్వండి.
- **தமிழ்** (Tamil) — ☕ விரைவில் வருகிறது. · ⭐ உங்களுக்கு இந்த ஆப்பும் எங்க வேலையும் பிடிச்சிருந்தா, தயவுசெஞ்சு இந்த ரெபாசிட்டரிக்கு ஒரு ஸ்டார்…
- **ગુજરાતી** (Gujarati) — ☕ ટૂંક સમયમાં આવી રહ્યું છે. · ⭐ જો તમને આ એપ અને અમારું કામ ગમતું હોય, તો પ્લીઝ રિપોઝિટરીને સ્ટાર આપો અને અમને ફોલો કરો.
- **ಕನ್ನಡ** (Kannada) — ☕ ಶೀಘ್ರದಲ್ಲೇ ಬರುತ್ತಿದೆ. · ⭐ ನಿಮಗೆ ಈ ಆ್ಯಪ್ ಮತ್ತು ನಮ್ಮ ಕೆಲಸ ಇಷ್ಟವಾದರೆ, ದಯವಿಟ್ಟು ರೆಪೊಸಿಟರಿಗೆ ಸ್ಟಾರ್ ಕೊಡಿ ಮತ್ತು ನಮ್ಮನ್ನು ಫಾಲೋ ಮಾಡಿ.
- **മലയാളം** (Malayalam) — ☕ ഉടൻ വരുന്നു. · ⭐ നിങ്ങൾക്ക് ഈ ആപ്പും ഞങ്ങളുടെ പ്രവർത്തനവും ഇഷ്ടപ്പെട്ടെങ്കിൽ, ദയവായി റെപ്പോസിറ്ററിക്ക് ഒരു സ്റ്റാർ…
- **ਪੰਜਾਬੀ** (Punjabi) — ☕ ਜਲਦੀ ਆ ਰਿਹਾ ਹੈ। · ⭐ ਜੇ ਤੁਹਾਨੂੰ ਇਹ ਐਪ ਅਤੇ ਸਾਡਾ ਕੰਮ ਪਸੰਦ ਹੈ, ਤਾਂ ਕਿਰਪਾ ਕਰਕੇ ਰਿਪੋਜ਼ਟਰੀ ਨੂੰ ਸਟਾਰ ਦਿਓ ਅਤੇ ਸਾਨੂੰ ਫਾਲੋ ਕਰੋ।
- **ଓଡ଼ିଆ** (Odia) — ☕ ଖୁବ୍ ଶୀଘ୍ର ଆସୁଛି। · ⭐ ଯଦି ଆପଣଙ୍କୁ ଏହି ଆପ୍ ଏବଂ ଆମର କାମ ଭଲ ଲାଗୁଛି, ତେବେ ଦୟାକରି ରିପୋଜିଟୋରୀକୁ ଗୋଟିଏ ଷ୍ଟାର୍ ଦିଅନ୍ତୁ ଏବଂ ଆମକୁ ଫଲୋ କରନ୍ତୁ।
- **অসমীয়া** (Assamese) — ☕ অতি সোনকালেই আহি আছে। · ⭐ যদি আপুনি এই এপটো আৰু আমাৰ কাম ভাল পায়, তেন্তে অনুগ্ৰহ কৰি ৰিপজিটৰীটোক এটা ষ্টাৰ দিয়ক আৰু আমাক ফ'ল' কৰক।
- **اردو** (Urdu) — ☕ جلد آرہا ہے۔ · ⭐ اگر آپ کو یہ ایپ اور ہمارا کام پسند آئے تو براہ کرم ریپازٹری کو سٹار دیں اور ہمیں فالو کریں۔
- **संस्कृतम्** (Sanskrit) — ☕ शीघ्रमेव आगच्छति।
- **मैथिली** (Maithili) — ☕ जल्दिये आबि रहल अछि।
- **कोंकणी** (Konkani) — ☕ रोखडेंच येता.
- **کٲشُر** (Kashmiri) — ☕ جَلدی یِوان چھُ۔
- **سنڌي** (Sindhi) — ☕ जल्द अची रह्यो आहे।
- **डोगरी** (Dogri) — ☕ जल्दी आ करदा ऐ।
- **ꯃꯤꯇꯩ ꯂꯣꯟ** (Manipuri) — ☕ ꯑꯊꯨꯕꯥ ꯃꯇꯝꯗꯥ ꯂꯥꯛꯀꯅꯤ꯫
- **बर’** (Bodo) — ☕ थाबैनो फैगोन।
- **ᱥᱟᱱᱛᱟᱲᱤ** (Santali) — ☕ ᱞᱚᱜᱚᱱ ᱦᱤᱡᱩᱜ ᱠᱟᱱᱟ᱾
- **नेपाली** (Nepali) — ☕ छिट्टै आउँदैछ। · ⭐ यदि तपाईंलाई यो एप र हाम्रो काम मन पर्‍यो भने, कृपया रिपोजिटरीलाई स्टार दिनुहोस् र हामीलाई फलो गर्नुहोस्।

**🌍 World languages**

- **العربية** (Arabic) — ☕ قريبًا. · ⭐ إذا أعجبك هذا التطبيق وعملنا، فيرجى منح المستودع نجمة ومتابعتنا.
- **简体中文** (Chinese (Simplified)) — ☕ 即将推出。
- **繁體中文** (Chinese (Traditional)) — ☕ 即將推出。
- **Čeština** (Czech) — ☕ Již brzy.
- **Dansk** (Danish) — ☕ Kommer snart.
- **Nederlands** (Dutch) — ☕ Binnenkort beschikbaar. · ⭐ Vind je deze app en ons werk leuk?
- **Filipino** (Filipino) — ☕ Malapit na.
- **Suomi** (Finnish) — ☕ Tulossa pian.
- **Français** (French) — ☕ Bientôt disponible. · ⭐ Si cette application et notre travail vous plaisent, mettez une étoile au dépôt et suivez-nous.
- **Deutsch** (German) — ☕ Bald verfügbar. · ⭐ Wenn dir diese App und unsere Arbeit gefallen, gib dem Repository einen Stern und folge uns.
- **Ελληνικά** (Greek) — ☕ Έρχεται σύντομα.
- **עברית** (Hebrew) — ☕ בקרוב.
- **Magyar** (Hungarian) — ☕ Hamarosan.
- **Bahasa Indonesia** (Indonesian) — ☕ Segera hadir. · ⭐ Jika kamu suka aplikasi ini dan karya kami, beri bintang pada repositori ini dan ikuti kami.
- **Italiano** (Italian) — ☕ In arrivo.
- **日本語** (Japanese) — ☕ 近日公開。 · ⭐ このアプリと私たちの仕事が気に入ったら、リポジトリにスターを付けてフォローしてください。
- **한국어** (Korean) — ☕ 곧 공개됩니다. · ⭐ 이 앱과 저희 작업이 마음에 드신다면 저장소에 스타를 눌러 주시고 팔로우해 주세요.
- **Bahasa Melayu** (Malay) — ☕ Akan datang.
- **Norsk** (Norwegian) — ☕ Kommer snart.
- **فارسی** (Persian) — ☕ به‌زودی.
- **Polski** (Polish) — ☕ Wkrótce. · ⭐ Jeśli podoba Ci się ta aplikacja i nasza praca, daj repozytorium gwiazdkę i obserwuj nas.
- **Português** (Portuguese) — ☕ Em breve.
- **Română** (Romanian) — ☕ În curând.
- **Русский** (Russian) — ☕ Скоро. · ⭐ Если вам нравится это приложение и наша работа, поставьте звёздочку репозиторию и подпишитесь на нас.
- **Español** (Spanish) — ☕ Muy pronto. · ⭐ Si te gusta esta app y nuestro trabajo, dale una estrella al repositorio y síguenos.
- **Kiswahili** (Swahili) — ☕ Inakuja hivi karibuni.
- **Svenska** (Swedish) — ☕ Kommer snart.
- **ไทย** (Thai) — ☕ เร็ว ๆ นี้
- **Türkçe** (Turkish) — ☕ Çok yakında.
- **Українська** (Ukrainian) — ☕ Незабаром.
- **Tiếng Việt** (Vietnamese) — ☕ Sắp ra mắt. · ⭐ Nếu bạn thích ứng dụng này và công việc của chúng tôi, hãy tặng kho lưu trữ một ngôi sao và theo…

</details>

