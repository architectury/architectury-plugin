# Architectury Plugin
Talk to us on [Discord](https://discord.gg/C2RdJDpRBP)!

Architectury Plugin is a gradle plugin to allow easier multi-modloader set-ups using a common module.

More documentation: [Architectury Wiki](https://architectury.github.io/architectury-documentations/docs/architectury_plugin/)

### Example Mod
- [Architectury Example Mod](https://github.com/architectury/architectury-example-mod)

### Important Information
- `Environment` and `EnvType` are remapped to `OnlyIn` and `Dist` on forge.

### Implementing Platform Specific APIs
_Notes: Only works for static methods._
![](https://media.discordapp.net/attachments/586186202781188108/776428814309785620/unknown.png?width=1191&height=439)

### IntelliJ Plugin
https://plugins.jetbrains.com/plugin/16210-architectury

### How does it work
Fabric Side:

- Module `fabric` depends on a transformed version of `common`, which is shaded afterwards
- A fake mod is generared, to let fabric load it on the correct class loader and let fabric load its assets

Forge Side:

- Module `forge` depends on a transformed version of `common`, which is shaded afterwards
- A fake mod is generated, to let forge load it on the correct class loader and let forge load its assets

### Usage
Your gradle version **MUST** be equals or above 5.5.1, all `assets` or `data` should go into the common module, with modloader specific files to their corresponding modules.
