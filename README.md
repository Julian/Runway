# Runway

An Android launcher because I couldn't find one I liked previously.

## Building

Requires the Android SDK with platform 37 and a JDK 17 or newer on the path.
On macOS with only Android Studio installed, register its bundled JDK once:

```sh
ln -s "/Applications/Android Studio.app/Contents/jbr" ~/Library/Java/JavaVirtualMachines/android-studio-jbr.jdk
```

```sh
./gradlew :app:assembleDebug
```

## Running

```sh
./gradlew installAsHome    # install the debug build, make it the home app, go home
```

Unit tests run on the host.
Instrumented tests drive the real launcher on an emulator, and replace its layout.
Gradle can create the emulators itself, from the devices declared in the build, which is what CI does.
It also installs the `fixture` module's app beside the launcher first: a stand-in that can be uninstalled and takes a web search, so those paths are tested on every image.

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:phonesGroupDebugAndroidTest      # every declared device, each booted and discarded
./gradlew :app:pixel10ProApi37DebugAndroidTest  # one of them
./gradlew :app:connectedDebugAndroidTest        # a connected emulator or device instead
```

Debug builds use the application id `com.grayvines.runway.debug` so they can be installed alongside a release build without touching its layout.

## Backups

Settings has "Save a backup" and "Restore a backup".
A backup is a JSON file of the settings and the layout: which apps and folders sit in which cells.
Restoring replaces both; apps that are not installed are left out, and widgets are not carried between devices.

## Baseline profile

Published releases ship a baseline profile, which has Android compile the launcher's hot paths at install time so the first frames, scrolls and drags do not run interpreted.
The `baselineprofile` module records it by driving the launcher on a Gradle-managed emulator, and the release build records a fresh one when given the `runwayProfile` property, which is how the published builds are made.

```sh
./gradlew :app:assembleRelease -PrunwayProfile
```

## Release builds

Release builds are minified and use the application id `com.grayvines.runway`.
They are signed when a PKCS#12 key (alias `runway`) is supplied through the `runwayStoreFile` and `runwayStorePassword` project properties, and left unsigned otherwise.

```sh
./gradlew installReleaseAsHome    # install the release build on a connected device as its home app
```
