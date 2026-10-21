# Ghost Schematic (Fabric, Minecraft 1.21.11)

Client-side only. Load a `.litematic`, `.schem` or `.nbt` and it looks like the build is really in the world.
Anywhere a real block already exists, the schematic block is simply not shown. Nothing is ever sent to the server.

## Build on GitHub
1. Create a new GitHub repo and upload everything in this folder (keep the `.github` folder).
2. Open the **Actions** tab. The `build` workflow runs on every push.
3. When it's green, open the run and download the **ghost-schematic** artifact (the jar inside).
4. Drop the jar in `.minecraft/mods` with Fabric Loader 0.18.1+ and **Fabric API for 1.21.11**.

No Gradle wrapper is needed; the workflow installs Gradle itself. To build locally: Java 21, then `gradle build`.

## Use
Put files in `.minecraft/schematics/` (same folder Litematica uses).

| Command | What it does |
|---|---|
| `/ghost load <file>` | Load a schematic, origin = your block position |
| `/ghost origin [x y z]` | Move the origin to you / given coords |
| `/ghost move <dx> <dy> <dz>` | Nudge the schematic |
| `/ghost rotate` | Rotate 90 degrees clockwise around the origin |
| `/ghost toggle` | Show / hide |
| `/ghost unload` | Remove it |
| `/ghost status`, `/ghost list`, `/ghost folder` | Info |

## How it works
Ghost blocks are written into the *client* world only where the block is air, so vanilla draws them with real
textures, lighting and transparency. Mixins make them intangible (walk through, crosshair passes through) and
detect when a real block replaces one. Server updates, your own placements and chunk reloads all remove the ghost.

## Limitations
- `.schematic` (old MCEdit format) is not supported; convert to `.schem`/`.litematic`.
- Water/lava/waterlogging are skipped (client-only fluid would desync swimming).
- Ladders, vines, cobwebs, fire etc. hide themselves when you get within ~3 blocks.
- Ghosts cast light/shadow like real blocks since they exist in the client world.
- Unknown blocks (renamed since the schematic was made) are skipped and listed in chat.
