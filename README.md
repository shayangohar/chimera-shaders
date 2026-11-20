<h1 align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/logo/chimera-logo-dark.svg">
    <img src="docs/logo/chimera-logo-light.svg" alt="Chimera" width="440">
  </picture>
</h1>

<p align="center">
  <a href="https://github.com/shayangohar/chimera-shaders/actions/workflows/build.yml"><img src="https://github.com/shayangohar/chimera-shaders/actions/workflows/build.yml/badge.svg" alt="Build status"></a>
</p>

Chimera runs [Iris](https://irisshaders.dev/) and [OptiFine](https://optifine.net/home) shader packs on [VulkanMod Reforged](https://modrinth.com/mod/vulkanmod-reforged). VulkanMod Reforged is itself a port of xCollateral's [VulkanMod](https://github.com/xCollateral/vulkanmod). Vulkan offers tremendous performance uplifts compared to Mojang's legacy OpenGL renderer, but I never liked using it because I couldn't run shaders with it. While other mods do allow shaders to run on Vulkan backends in newer versions of Minecraft, I wanted to fill the gap for the older versions used for modding. 

Thus far, Chimera has been tested on 46 different shader packs, which you can find in the [test set](https://github.com/shayangohar/chimera-shaders/wiki/Compatibility).

<p align="center">
  <img src="docs/screenshots/rtx-remix-complementary-reimagined.jpg" alt="RTX Remix world with Complementary Reimagined running on Chimera">
  <br>
  <em>RTX Remix world with Complementary Reimagined running on Chimera</em>
</p>

## Status

I've finally reached a point where I'm happy with Chimera, but I still consider it to be in a **Beta**. On my machine<sup>1</sup>, I see around a 10-15% FPS uplift in my testing scene (sometimes up to 20%), but this will not hold across all scenes, all machines, all software, and all modpacks. I hope to keep improving on Chimera's functionality, compatibility, and expanding it to harness more of Vulkan's modern features. I am an undergraduate studying CS, so I might not have the most time to work on Chimera, but I see this mod as a pet project of mine and intend to see it realized. 

<sup>1</sup> _Tested on an RTX 5080, R7 9800X3D, 32 GB DDR5 RAM @ 1440p 16 chunks render distance. You may see different results on your machine or different results depending on the scene._

## Requirements

- Minecraft 1.21.1 with [NeoForge](https://neoforged.net/) 21.1 or newer
- [VulkanMod Reforged](https://modrinth.com/mod/vulkanmod-reforged) 0.3.5-ALPHA+1.21.1
- Java 21

## Installing

1. Install NeoForge and VulkanMod Reforged.
2. Put the Chimera jar in your `mods` folder.
3. Put shader packs in the `shaderpacks` folder.
4. Start the game and press O to choose a pack, or open video settings.

## Documentation

- [FAQ](https://github.com/shayangohar/chimera-shaders/wiki/FAQ)
- [Using Chimera](https://github.com/shayangohar/chimera-shaders/wiki/Using-Chimera)
- [Compatibility](https://github.com/shayangohar/chimera-shaders/wiki/Compatibility)
- [Known issues](https://github.com/shayangohar/chimera-shaders/wiki/Known-issues)
- [Reporting bugs](https://github.com/shayangohar/chimera-shaders/wiki/Reporting-bugs)
- [Building from source](https://github.com/shayangohar/chimera-shaders/wiki/Building-from-source)

## Building

Use JDK 21.

```bash
./gradlew build
```

The jar is written to `build/libs/`.

## Credits

Obviously, Chimera would not exist without [Iris](https://github.com/IrisShaders/Iris), so massive thanks to [IMS](https://github.com/IMS212) and the [Iris team](https://github.com/IrisShaders). 

Additional thanks to xCollateral and Rindw for [VulkanMod](https://github.com/xCollateral/VulkanMod) and [VulkanMod's NeoForge fork](https://modrinth.com/mod/vulkanmod-reforged), respectively.

## License

[LGPL-3.0-only](LICENSE). The English interface text is adapted from Iris; see [NOTICE](NOTICE). VulkanMod Reforged is a separate download and is not included in Chimera's jar.

Chimera is not affiliated with or endorsed by Iris, OptiFine, VulkanMod or VulkanMod Reforged. Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.
