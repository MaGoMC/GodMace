# GodMace for Fabric 26.2 — source reconstruction (UNTESTED)

This is **not** a finished playable mod. It is a first-pass Java source reconstruction from the uploaded VideoMace 1.0 Bukkit/Paper plugin. The original compiled bytecode was inspected, but the entire feature set has **not** been reproduced exactly. No claim of successful compilation or in-game testing is made.

## Implemented in source
- `/getmace` and `/setmacekills` commands (operator-only)
- Tagged vanilla mace with kill count stored in custom data
- Right-click orbital TNT attack and shift-right-click stab attack
- Charge/recharge tracking and hit bonus damage

## Not yet ported / limitations
- Explosion-based kill attribution and exact kill counter attribution
- Original enchantment scaling, passive maximum health and exact potion-effect tiers
- Original explosion ownership, PvP ban mechanics, exact cooldowns and damage rules
- Exact custom crafting recipe, resource pack/model and particle effects
- Command and callback signatures may require adjustments to match the **actual** Minecraft 26.2 mappings and Fabric API.
- Explosion attack is destructive: test only in a disposable world.

## Build on your machine
1. Install **JDK 25** and **Gradle 9.5.1**.
2. Open a terminal in this directory and run `gradle build`.
3. If successful, the jar will be under `build/libs/`.
4. Install Fabric Loader for Minecraft 26.2 and a compatible Fabric API; put the resulting mod JAR and Fabric API JAR into `mods`.
5. Check compiler errors carefully. This source has not been built or validated.

Official docs: https://docs.fabricmc.net/develop/ and https://www.fabricmc.net/2026/06/15/262.html

This project is intentionally marked as a **work-in-progress**, not a faithful or verified drop-in conversion.

## October 29 update
Added an experimental death-event kill counter and inventory-based passive potion effects. These are not confirmed to match the original plugin and may need API name corrections. This project has NOT compiled successfully in this environment: no Java 25, Gradle, or network access to Fabric Maven. Do not put this ZIP into the mods folder.
