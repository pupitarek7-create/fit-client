# Fit Client

A small Fabric client for Minecraft 1.21.11 with a tabbed menu and a sus chunk finder.

- Open the menu with **Right Shift**.
- Modules tab: Render > **Sus Chunks**. Left-click toggles it, right-click opens its settings.
- When on, flagged chunks are listed on the HUD (top right) and announced in chat.

## Build on GitHub (no setup needed)

1. Create a new repository on github.com and push (or upload) this folder, including the `.github` folder.
2. Open the **Actions** tab. The *Build Fit Client* workflow runs on every push.
3. Open the finished run and download the **fit-client** artifact: it contains the jar.
4. Optional: push a tag such as `v1.0.0` and the jar is also attached to a GitHub release.

## Build locally

1. Copy `gradlew`, `gradlew.bat` and the `gradle/` folder from any Fabric project (for example your
   anubis-addon-template) into this folder, or run `gradle wrapper` if you have Gradle installed.
2. Check https://fabricmc.net/develop and update `gradle.properties` if the versions are out of date.
3. `./gradlew build` (Windows: `gradlew.bat build`). The jar is in `build/libs/fit-client-1.0.0.jar`.
4. `./gradlew runClient` starts a dev client.

Needs JDK 21.

## License / credit

GPL-3.0-only (see LICENSE). The sus chunk finder follows the "Classic" method of the Anubis client
(GPL-3.0, author 4ldenz): fully grown plants as a sign of nearby players, spread over the simulation
distance. The code in this project was written fresh for Fit Client.
