# Third-party components and licences

SecureLink for Android is a modified version of linphone-android (GPLv3) and is distributed under the **GNU GPL v3**.
This list covers what is built into the app: the Linphone SDK 5.5.29 built from source with the post-quantum
engine (`gradle.properties` → `LinphoneSdkBuildDir`), and the app's Gradle dependencies (`gradle/libs.versions.toml`).
No analytics and no crash reporting: Belledonne's `google-services.json` is removed, so neither Firebase Crashlytics
nor Firebase Messaging is initialised.

## Linphone SDK (Belledonne Communications)
| component | licence |
|---|---|
| liblinphone, mediastreamer2, ortp | GNU AGPL v3 |
| bctoolbox, belle-sip, belr, belcard, lime, bzrtp, postquantumcryptoengine, bcmatroska2 | GNU GPL v3 |

## Libraries built into the SDK
| component | licence |
|---|---|
| liboqs (post-quantum algorithms) | MIT |
| decaf (X448) | public domain / MIT |
| Mbed TLS | Apache 2.0 |
| libsrtp2 | BSD 3-clause |
| Opus | BSD 3-clause |
| Speex, SpeexDSP | BSD 3-clause |
| GSM 06.10 | permissive (Jutta Degener, Carsten Bormann, TU Berlin) |
| BroadVoice16 | BSD-style (Broadcom) |
| WebRTC echo canceller / VAD (mswebrtc) | BSD 3-clause |
| libvpx, libaom, dav1d, libyuv (video – compiled in, switched off in the app) | BSD 2/3-clause |
| libjpeg-turbo | IJG / BSD 3-clause / zlib |
| ZXing-C++ (QR code reader) | Apache 2.0 |
| JsonCpp | public domain / MIT |
| Xerces-C++ | Apache 2.0 |
| SOCI | Boost Software License 1.0 |
| SQLite | public domain |
| OpenLDAP (client library) | OpenLDAP Public License 2.8 |
| libxml2 | MIT |
| zlib | zlib licence |

## Android libraries (Gradle)
| library | licence |
|---|---|
| AndroidX (annotation, appcompat, constraintlayout, core, core-splashscreen, core-telecom, media, recyclerview, swiperefreshlayout, slidingpanelayout, window, gridlayout, security-crypto, navigation, emoji2, car-app) | Apache 2.0 |
| Material Components, Flexbox | Apache 2.0 |
| Protocol Buffers (javalite) | BSD 3-clause |
| Firebase Messaging / Crashlytics client (present in the build, not initialised – no configuration file) | Apache 2.0 (uses Google Play services) |
| Coil (image loading) | Apache 2.0 |
| dotsindicator | Apache 2.0 |
| PhotoView | Apache 2.0 |
| AppAuth for Android | Apache 2.0 |
