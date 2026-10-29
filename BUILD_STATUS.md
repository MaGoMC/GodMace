# Build status: NOT VERIFIED

This is a **work-in-progress source port**, NOT a playable Minecraft mod.

The original Bukkit plugin JAR is included separately in the user's conversation, not in this source project. This project contains reconstructed source that has NOT been compiled, run, or verified against the actual Fabric 26.2 APIs. The Minecraft, Loader, Fabric API, and Loom version strings are provisional and may not exist or be compatible. No build dependencies can be downloaded in the current offline environment, and the available JDK is 21 rather than 25.

To try an automated build, upload the entire project folder to a GitHub repository and use Actions -> Build GodMace Fabric -> Run workflow. If the build fails, inspect the compiler errors; do not assume the output is playable. Even a successful compilation does not guarantee that gameplay matches the original plugin.

Original plugin functions still missing or incomplete include precise passive tiers, max-health scaling, explosion attribution, crafting, damage/cooldowns, and PvP behavior. TNT is destructive; only test on a disposable world.
