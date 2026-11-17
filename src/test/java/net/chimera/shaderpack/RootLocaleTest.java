package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Names are compared the same way under any default locale; Turkish lower-cases I to a dotless ı. */
final class RootLocaleTest {
    @Test
    void aZipExtensionReadsAsZipUnderTurkish() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("Pack", PackProbe.logicalPackName(Path.of("Pack.ZIP")));
        } finally {
            Locale.setDefault(before);
        }
    }
}
