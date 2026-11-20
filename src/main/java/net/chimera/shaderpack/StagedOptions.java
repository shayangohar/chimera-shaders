// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import net.chimera.shaderpack.options.OptionFile;
import net.chimera.shaderpack.options.OptionValues;
import net.chimera.shaderpack.options.PackProfiles;
import net.chimera.shaderpack.options.ShaderPackOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The options a loaded pack was staged with. {@code pack} holds the stored values, pending screen changes
 * included; {@code applied} adds the JVM overrides, and {@code undeclared} holds the overrides the pack does not
 * declare, left to the preprocessor as locked macros.
 */
public record StagedOptions(ShaderPackOptions pack, OptionValues applied, Map<String, String> undeclared,
                            Set<String> disabledPrograms, boolean optionFileReadable) {
    public StagedOptions {
        undeclared = Map.copyOf(undeclared);
        disabledPrograms = Set.copyOf(disabledPrograms);
    }

    /** The applied values as shaders.properties sees them. */
    PackOptionEnvironment environment() {
        return PackOptionEnvironment.of(pack, applied, disabledPrograms);
    }

    /** The profile the applied values match, or {@code Custom} when none does, as Iris names it. */
    public String profileName() {
        return pack.profiles().match(applied).current().map(PackProfiles.Profile::name).orElse("Custom");
    }

    /**
     * Iris's profile line, for the log and F3: {@code Profile: HIGH (+1 option changed by user)},
     * counting the changes beyond those the profile makes (ShaderPack.profileInfo).
     */
    public String profileInfo() {
        Optional<PackProfiles.Profile> profile = pack.profiles().match(applied).current();
        int byProfile = profile.map(match -> OptionValues.of(applied.options(), match.values()).changedCount())
                .orElse(0);
        int byUser = applied.changedCount() - byProfile;
        return "Profile: " + profile.map(PackProfiles.Profile::name).orElse("Custom") + " (+" + byUser + " option"
                + (byUser == 1 ? "" : "s") + " changed by user)";
    }

    /**
     * Brings {@code <pack>.txt} in line with the stored values, as Iris rewrites it after every load; JVM overrides
     * are never written. The file is left alone when Chimera could not read the pack or its option file, so values
     * it could not see are never dropped.
     */
    public void saveOptionFile(Path packPath) throws IOException {
        boolean noOptions = pack.options().toggles().isEmpty() && pack.options().choices().isEmpty();
        if (!optionFileReadable || !pack.complete() || noOptions) return;
        Path file = OptionFile.of(packPath);
        Map<String, String> stored = pack.values().changes();
        boolean current = stored.equals(OptionFile.read(file)) && Files.exists(file) != stored.isEmpty();
        if (!current) OptionFile.write(file, pack.values());
    }

    /** The options of a pack whose sources could not be read. */
    static StagedOptions none() {
        ShaderPackOptions pack = ShaderPackOptions.empty();
        return new StagedOptions(pack, pack.values(), Map.of(), Set.of(), true);
    }
}
