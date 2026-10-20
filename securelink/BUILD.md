# Building SecureLink Talk for Android

SecureLink Talk for Android is built from two sources:

1. **This repository** (branch `securelink`): a modified [linphone-android](https://gitlab.linphone.org/BC/public/linphone-android)
   6.2.8. Every released APK has a tag `android-<version>` that points to its exact source.
2. **linphone-sdk 5.5.29** (commit `80bf7c1d269a185dbe30248271c6590887637e4f`), built with the post-quantum
   engine. The SDK published on Belledonne's Maven repository does not contain it, so the SDK is built locally.

## 1. linphone-sdk

Requirements: Android SDK with NDK 27.2.12479018, CMake, Java 17 (the SDK's Gradle packaging does not run on newer
Java), Python 3 with `pystache`.

```sh
git clone --recursive https://gitlab.linphone.org/BC/public/linphone-sdk.git linphone-sdk-src
cd linphone-sdk-src
git checkout 80bf7c1d269a185dbe30248271c6590887637e4f
git submodule update --init --recursive
git apply ../securelink-android/securelink/linphone-sdk-5.5.29-no-secrets-in-log.patch
export JAVA_HOME=<Java 17> ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018
cmake --preset=android-sdk -B build-android -DCMAKE_BUILD_TYPE=RelWithDebInfo \
  -DENABLE_NON_FREE_FEATURES=ON -DENABLE_PQCRYPTO=ON -DLINPHONESDK_ANDROID_ARCHS=arm64,x86_64 \
  -DCMAKE_ANDROID_NDK=$ANDROID_NDK_HOME
cmake --build build-android --parallel
```

- `no-secrets-in-log`: remote provisioning logged every entry with its value, the account password included; the
  password, digest hash, keys and tokens are now logged as `***`.
- `ENABLE_NON_FREE_FEATURES=ON` is required by the build system for `ENABLE_PQCRYPTO`. It adds no patent-encumbered
  codec (no H.264, AMR or G.729; the audio codecs are Opus, Speex, GSM and BV16). Video is compiled in but switched off in the app.
- The post-quantum engine (`postquantumcryptoengine`) is GPLv3, liboqs is MIT.
- `x86_64` is for the emulator only; release APKs contain `arm64-v8a`.

The result is the Maven repository `build-android/maven_repository` with `org.linphone:linphone-sdk-android:5.5.29`.

## 2. The app

Point `LinphoneSdkBuildDir` in `gradle.properties` to your `build-android` folder, then:

```sh
./gradlew assembleDebug        # debug build (also trusts a lab certificate authority, see app/src/debug)
./gradlew assembleRelease      # release build, signed when SECURELINK_KEYSTORE and SECURELINK_KEYSTORE_PASSWORD are set
```

To install a build of your own next to ours, change the application id `cc.securelink.talk` and use your own
Firebase project (`app/google-services.json`; without that file the app builds without push notifications).

## What the app connects to

The app has no server built in for users to sign up on. A phone is set up by scanning a QR code from a
SecureLink server (`https://…securelink.cc` only); the server runs unmodified Flexisip and lime-server.
`app/src/main/assets/securelink-ca.pem` is the only certificate authority the app trusts for SIP over TLS.
Push notifications go through Google Firebase Cloud Messaging and only wake the phone: they carry no caller,
sender or message text.
