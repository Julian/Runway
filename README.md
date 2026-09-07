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

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:phonesGroupDebugAndroidTest      # every declared device, each booted and discarded
./gradlew :app:pixel10ProApi37DebugAndroidTest  # one of them
./gradlew :app:connectedDebugAndroidTest        # a connected emulator or device instead
```

Debug builds use the application id `com.grayvines.runway.debug` so they can be installed alongside a release build without touching its layout.

## Release builds

Release builds are minified and use the application id `com.grayvines.runway`.
They are signed when a PKCS#12 key (alias `runway`) is supplied through the `runwayStoreFile` and `runwayStorePassword` project properties, and left unsigned otherwise.

```sh
./gradlew installReleaseAsHome    # install the release build on a connected device as its home app
```
