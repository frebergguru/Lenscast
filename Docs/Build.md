# Building Lenscast (CLI, no Android Studio)

Lenscast is built with Gradle from the command line. You do not need Android Studio.

## Required tools

| Tool          | Tested version       | Where to get it on Manjaro/Arch                  |
|---------------|----------------------|--------------------------------------------------|
| JDK           | 21 (pinned; AGP 8.5 supports 17–21) | `pacman -S jdk21-openjdk`         |
| Android SDK   | `platforms;android-35`, `build-tools;35.0.0` | AUR `android-sdk-cmdline-tools-latest` + manual `sdkmanager` |
| adb           | Any recent           | `pacman -S android-tools`                        |

Gradle and the Android Gradle Plugin are pulled by the wrapper on first build — no separate install.

## One-time setup

```bash
# 1. JDK + adb
sudo pacman -S jdk21-openjdk android-tools
sudo archlinux-java set java-21-openjdk

# 2. SDK — either AUR
yay -S android-sdk-cmdline-tools-latest android-platform android-sdk-build-tools
# or manual: download commandlinetools-linux-*.zip from developer.android.com
#   and extract under ~/Android/Sdk/cmdline-tools/latest/

# 3. Environment (add to ~/.zshrc or ~/.bashrc)
export ANDROID_HOME=$HOME/Android/Sdk        # or /opt/android-sdk for AUR
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

# 4. SDK components + license acceptance
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
yes | sdkmanager --licenses

# 5. Tell Gradle where the SDK lives (project-local, gitignored)
cd path/to/Lenscast
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

## JDK pinning

`gradle.properties` ships with:

```
org.gradle.java.home=/usr/lib/jvm/java-21-openjdk
```

This pins the Gradle daemon to JDK 21 so newer system JDKs (22+, 24+, 26+) don't break the
build. AGP 8.5 supports 17–21 only. Edit or remove this line if your JDK lives somewhere
else or if you prefer to use `$JAVA_HOME`.

## Build + install loop

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The installed package id is `guru.freberg.lenscast` for both debug and release variants — the
debug build deliberately shares the applicationId so `adb install -r` upgrades whichever
copy is already on the device.

To start the app remotely after install:

```bash
adb shell monkey -p guru.freberg.lenscast -c android.intent.category.LAUNCHER 1
```

## What gets downloaded on first build

About 600 MB of dependencies (Gradle distribution, AGP, Kotlin compiler, Compose BOM,
CameraX, DataStore, coroutines). Subsequent builds are ~5–15 seconds.

## Suppressed AGP warning

AGP 8.5.2 prints "tested up to compileSdk = 34" when used with `compileSdk = 35`. That
warning is suppressed via `android.suppressUnsupportedCompileSdk=35` in
`gradle.properties` — the build works fine with the current versions. Bump AGP to 8.7+
later to drop the suppression.
