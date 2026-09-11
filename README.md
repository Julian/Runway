# Runway

An Android launcher because I couldn't find one I liked previously.

## Building

Requires the Android SDK with platform 37 and a JDK 21 or newer on the path.
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
`./gradlew preflight` formats the code and then runs detekt, lint and the unit tests, which is what CI checks; run it before committing, since the git hooks do not.
Instrumented tests drive the real launcher on an emulator, and replace its layout.
Gradle can create the emulators itself, from the devices declared in the build, which is what CI does.
It also installs the `fixture` module's app beside the launcher first: a stand-in that can be uninstalled and takes a web search, so those paths are tested on every image.

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:phonesGroupDebugAndroidTest      # every declared device at once, each booted and discarded
./gradlew :app:pixel10ProApi37DebugAndroidTest  # one of them
./gradlew :app:connectedDebugAndroidTest        # a connected emulator or device instead
```

The managed emulators draw with the host GPU and take about five minutes for the whole suite on both devices; stop any other emulator first, since three at once can run the machine out of memory.

Debug builds use the application id `com.grayvines.runway.debug` so they can be installed alongside a release build without touching its layout.

## Backups

Settings has "Save a backup" and "Restore a backup".
A backup is a JSON file of the settings and the layout: which apps and folders sit in which cells, and the folders in the drawer.
Restoring replaces both; apps that are not installed are left out.
Widgets are not in the file yet, so the widgets already on the device stay where they are, and a restored icon that would land on one is left out.

## Baseline profile

Published releases ship a baseline profile, which has Android compile the launcher's hot paths at install time so the first frames, scrolls and drags do not run interpreted.
The `baselineprofile` module records it by driving the launcher on a Gradle-managed emulator, and the release build records a fresh one when given the `runwayProfile` property, which is how the published builds are made.

```sh
./gradlew :app:assembleRelease -PrunwayProfile
```

## Release builds

Release builds are minified and use the application id `com.grayvines.runway`.
They are signed when a PKCS#12 key (alias `runway`) is supplied through the `runwayStoreFile` and `runwayStorePassword` project properties, and left unsigned otherwise.

An unsigned release build cannot be installed, so this needs the key.

```sh
./gradlew installReleaseAsHome    # install the signed release build on a connected device as its home app
```
