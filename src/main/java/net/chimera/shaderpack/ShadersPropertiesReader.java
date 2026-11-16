package net.chimera.shaderpack;

import net.chimera.shaderpack.PackSettingsPlan.Option;
import net.chimera.shaderpack.PackSettingsPlan.Profile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.chimera.shaderpack.PackSettingsPlan.COMMENTED_DEFINE;
import static net.chimera.shaderpack.PackSettingsPlan.DEFINE;
import static net.chimera.shaderpack.PackSettingsPlan.PASS_TEXTURE_STAGES;
import static net.chimera.shaderpack.PackSettingsPlan.isDimensionContextMacro;
import static net.chimera.shaderpack.PackSettingsPlan.isStageContextMacro;
import static net.chimera.shaderpack.PackSettingsPlan.stageIndependentDefaults;
import static net.chimera.shaderpack.PackSettingsPlan.valuesFrom;

/**
 * Reads shaders.properties as Iris's ShaderProperties does. Lines join across continuations, conditional
 * blocks evaluate with the option macros, and each entry goes into PackSettingsPlan's maps: option defines,
 * profiles, features, custom values, program switches and resource declarations. Other properties files go
 * through PropertiesPreprocessor.
 */
final class ShadersPropertiesReader {
    private static final Pattern PROFILE = Pattern.compile(
            "^profile\\.([A-Za-z_]\\w*)\\s*=\\s*(.*)$");
    private static final String PROGRAM_PREFIX = "program.";
    private static final Pattern FEATURES = Pattern.compile(
            "^iris\\.features\\.(required|optional)\\s*=\\s*(.*)$");
    private static final Pattern CUSTOM_VALUE = Pattern.compile(
            "^\\s*(uniform|variable)\\.(float|int|bool|vec2|vec3|vec4)\\.([A-Za-z_]\\w*)\\s*=\\s*(.*)$");
    private static final Pattern CONDITION = Pattern.compile(
            "^\\s*#(if|ifdef|ifndef|elif|else|endif)\\b(.*)$");

    private ShadersPropertiesReader() {}

    /** Fills the given maps and returns the macros in force at the end of the file. */
    static Map<String, String> read(
            Path propertyFile,
            Map<String, Option> options,
            Map<String, String> defaults,
            Map<String, Profile> profiles,
            Map<String, String> values,
            Map<String, Boolean> programEnabled,
            Set<String> required,
            Set<String> optional,
            List<PackRuntimeSettings.Declaration> customValues,
            Map<String, PackResourceDeclaration> resourceDeclarations,
            List<String> deviations,
            Map<String, String> initialMacros,
            Set<String> toggleOptions
    ) {
        PropertiesParse parse = new PropertiesParse(propertyFile, options, defaults, profiles, values,
                programEnabled, required, optional, customValues, resourceDeclarations, deviations, toggleOptions);
        // Iris's macro set is taken whole, since SHADOW or DH_TERRAIN may be the pack's own options
        parse.run(initialMacros == null ? stageIndependentDefaults(defaults) : initialMacros);
        return parse.conditions.macros();
    }

    private static final class PropertiesParse {
        private final Path propertyFile;
        private final Map<String, Option> options;
        private final Map<String, String> defaults;
        private final Map<String, Profile> profiles;
        private final Map<String, String> values;
        private final Map<String, Boolean> programEnabled;
        private final Set<String> required;
        private final Set<String> optional;
        private final List<PackRuntimeSettings.Declaration> customValues;
        private final Map<String, PackResourceDeclaration> resourceDeclarations;
        private final List<String> deviations;
        private final Set<String> toggleOptions;
        private PackConditionals.State conditions;
        // an #ifdef with no name takes the next line with text, as JCPP reads it
        private boolean takeNextLine;

        PropertiesParse(Path propertyFile, Map<String, Option> options, Map<String, String> defaults,
                        Map<String, Profile> profiles, Map<String, String> values,
                        Map<String, Boolean> programEnabled, Set<String> required, Set<String> optional,
                        List<PackRuntimeSettings.Declaration> customValues,
                        Map<String, PackResourceDeclaration> resourceDeclarations, List<String> deviations,
                        Set<String> toggleOptions) {
            this.propertyFile = propertyFile;
            this.options = options;
            this.defaults = defaults;
            this.profiles = profiles;
            this.values = values;
            this.programEnabled = programEnabled;
            this.required = required;
            this.optional = optional;
            this.customValues = customValues;
            this.resourceDeclarations = resourceDeclarations;
            this.deviations = deviations;
            this.toggleOptions = toggleOptions;
        }

        void run(Map<String, String> initialMacros) {
            conditions = new PackConditionals.State(initialMacros);
            try {
                // ISO-8859-1 as Iris reads it, which never fails on a stray byte
                // JCPP carries a block comment across lines
                List<PropertiesPreprocessor.Line> lines = PropertiesPreprocessor.commentLines(
                        Files.readAllLines(propertyFile, StandardCharsets.ISO_8859_1));
                PropertyLineJoiner joiner = new PropertyLineJoiner();
                for (int index = 0; index <= lines.size(); index++) {
                    PropertiesPreprocessor.Line next = index < lines.size() ? lines.get(index) : null;
                    String raw = next == null ? null : next.text();
                    String line;
                    if (raw != null && PropertyLineJoiner.endsContinuation(raw) && joiner.pending()) {
                        // a # line ends the open continuation: handle that line first, then revisit the # line
                        line = joiner.finish();
                        index--;
                    } else if (raw == null) {
                        line = joiner.finish();
                    } else if (skips(next)) {
                        continue;
                    } else {
                        line = PropertyLineJoiner.endsContinuation(raw) ? raw : joiner.accept(raw);
                    }
                    if (line != null) {
                        accept(line);
                    }
                }
                try {
                    conditions.finish();
                } catch (RuntimeException failure) {
                    deviations.add("PREPROCESSOR_CONDITION_UNSUPPORTED");
                }
            } catch (IOException e) {
                deviations.add("SHADERS_PROPERTIES_READ_FAILED");
            }
        }

        /**
         * Whether a line adds nothing to the settings. That is a {@code #} line inside a block comment, which
         * JCPP reads as comment text and Properties as a comment; a conditional, which this applies; a line of
         * an inactive branch; or the line an #ifdef with no name takes.
         */
        private boolean skips(PropertiesPreprocessor.Line next) {
            if (takeNextLine) {
                // the first line Iris's line pass leaves text on is taken; the emptied ones go anyway
                takeNextLine = PropertiesPreprocessor.emptiedByLinePass(next.text());
                return true;
            }
            if (next.inComment() && PropertyLineJoiner.endsContinuation(next.text())) return true;
            return applyCondition(next.text()) || !conditions.active();
        }

        /**
         * Applies a conditional directive line as JCPP does, reading on past one Chimera cannot evaluate;
         * false when the line is not one.
         */
        private boolean applyCondition(String raw) {
            Matcher directive = CONDITION.matcher(raw);
            if (!directive.matches()) {
                return false;
            }
            PropertiesPreprocessor.Conditional applied = PropertiesPreprocessor.applyConditional(
                    conditions, directive.group(1), directive.group(2).trim());
            if (applied != PropertiesPreprocessor.Conditional.APPLIED) {
                deviations.add("PREPROCESSOR_CONDITION_UNSUPPORTED");
            }
            takeNextLine = applied == PropertiesPreprocessor.Conditional.TAKES_NEXT_LINE;
            return true;
        }

        /** One logical line: a define, a commented-out define, a comment, or a key=value entry. */
        private void accept(String line) {
            if (acceptDefine(line) || line.trim().startsWith("#")) {
                return;
            }
            int equals = line.indexOf('=');
            if (equals < 1) {
                return;
            }
            String key = line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim();
            if (key.isBlank()) {
                return;
            }
            // Iris runs the file through a C preprocessor, so a resource's value reads the macros defined so far
            // Alpha Piscium sizes its restir textures with RESTIR_REUSE_TEX_WIDTH
            // switches and option lists keep their names, since Chimera evaluates those itself
            if (RESOURCE_PREFIXES.stream().anyMatch(key::startsWith)) {
                value = PackConditionals.expandMacros(value, conditions.macros()).trim();
            }
            values.put(key, value);
            acceptEntry(line, key, value);
        }

        /** A #define declares an option and its default; a commented-out one declares it off. */
        private boolean acceptDefine(String line) {
            Matcher define = DEFINE.matcher(line);
            if (define.matches()) {
                String name = define.group(1);
                if (isStageContextMacro(name) || isDimensionContextMacro(name)) {
                    return true;
                }
                boolean bare = define.group(2) == null || define.group(2).isBlank();
                String value = bare ? "1" : PropertiesPreprocessor.withoutComments(define.group(2)).trim();
                options.putIfAbsent(name, new Option(name, "define", value, valuesFrom(line),
                        propertyFile.getFileName().toString()));
                defaults.put(name, value);
                // a bare #define is empty to JCPP: #ifdef holds, #if fails
                conditions.define(name, bare ? "" : value);
                return true;
            }
            Matcher commentedDefine = COMMENTED_DEFINE.matcher(line);
            if (!commentedDefine.matches()) {
                return false;
            }
            String name = commentedDefine.group(1);
            options.putIfAbsent(name, new Option(name, "define", "0", valuesFrom(line),
                    propertyFile.getFileName().toString()));
            return true;
        }

        private void acceptEntry(String line, String key, String value) {
            PackResourceDeclaration resource = resourceDeclaration(key, value);
            if (resource != null) {
                resourceDeclarations.put(resource.key(), resource);
                return;
            }
            Matcher profile = PROFILE.matcher(line);
            if (profile.matches()) {
                profiles.put(profile.group(1), new Profile(profile.group(1), value));
                return;
            }
            String program = switchedProgram(key);
            if (program != null) {
                acceptProgramSwitch(program, value.trim());
                return;
            }
            Matcher feature = FEATURES.matcher(line);
            if (feature.matches()) {
                Set<String> destination = feature.group(1).equals("required") ? required : optional;
                destination.clear();
                // split on spaces only, as Iris does, so "A,B" is one unknown flag
                for (String token : feature.group(2).trim().split(" +")) {
                    if (!token.isBlank()) {
                        destination.add(token);
                    }
                }
                return;
            }
            Matcher custom = CUSTOM_VALUE.matcher(line);
            if (custom.matches()) {
                // option values expand as macros, as in Iris; Reverie's betaRfact reads RAYLEIGH_DENSITY_VARIANCE
                customValues.add(PackRuntimeSettings.declaration(
                        custom.group(1).equals("uniform"), custom.group(2),
                        custom.group(3), PackConditionals.expandMacros(custom.group(4), conditions.macros()).trim()));
                return;
            }
            String unread = unreadKey(key);
            if (unread != null) deviations.add(unread + ":" + key);
        }

        /**
         * The deviation for a key no other reading took, or null for a property Chimera reads elsewhere
         * (PackPropertyKeys). AdvancedImageDeclarations reports an {@code image.*} it cannot serve.
         */
        private static String unreadKey(String key) {
            // recorded only; the storage buffer plan checks for a matching std430 block and a supported schedule
            if (key.startsWith("bufferObject.")) return "STORAGE_BUFFER_DECLARED";
            if (key.startsWith("texture.")) return "TEXTURE_STAGE_UNKNOWN";
            return PackPropertyKeys.unread(key);
        }

        /**
         * The program a {@code program.<name>.<anything>} key switches, or null for any other key. Iris takes
         * the name up to the first dot after {@code program.}, with any suffix; Solas writes
         * {@code program.world0/deferred..enabled}.
         */
        private static String switchedProgram(String key) {
            int end = key.indexOf('.', PROGRAM_PREFIX.length());
            return key.startsWith(PROGRAM_PREFIX) && end > PROGRAM_PREFIX.length()
                    ? key.substring(PROGRAM_PREFIX.length(), end) : null;
        }

        /** A {@code program.<name>.enabled} switch, evaluated against the pack's option values as Iris does. */
        private void acceptProgramSwitch(String program, String expression) {
            try {
                // a toggle the pack declares but leaves off (`//#define DOF`) is false
                // only a name that is no option at all keeps the program enabled
                Map<String, String> switches = new HashMap<>(conditions.macros());
                for (String toggle : toggleOptions) {
                    switches.putIfAbsent(toggle, "0");
                }
                programEnabled.put(program, PackConditionals.evaluate(expression, switches, true));
            } catch (RuntimeException failure) {
                programEnabled.put(program, true);
                deviations.add("PROGRAM_ENABLE_EXPRESSION_UNSUPPORTED:" + program);
            }
        }
    }

    /**
     * Joins shaders.properties' backslash continuations as Iris's PropertiesPreprocessor and Properties loader
     * do.
     *
     * Iris drops blank lines before preprocessing, so a blank line does not end a continuation. Every
     * {@code #} line becomes an empty line, a comment at once and a directive once JCPP has read it, and an
     * empty line ends a continuation in Properties.load. Photon closes a multi-line
     * {@code moon_phase_brightness} with {@code ") \"} right before its {@code #else}, and Bliss's
     * {@code screen} list ends at the commented-out one under it. The id maps differ: IdMap rejoins across
     * those lines ({@link IdMapProperties}).
     *
     * Callers evaluate conditions on the lines JCPP sees (PropertiesPreprocessor.commentLines), end an open
     * continuation at each {@code #} line, and feed only the other active lines to {@link #accept}.
     */
    static final class PropertyLineJoiner {
        private final StringBuilder pending = new StringBuilder();

        /** A {@code #} line, directive or comment, which ends an open continuation. */
        static boolean endsContinuation(String raw) {
            return raw.strip().startsWith("#");
        }

        boolean pending() {
            return pending.length() > 0;
        }

        /**
         * The completed logical line, or null while a continuation is open or the line is dropped.
         * Leading whitespace goes, as Properties.load skips it: Photon indents its
         * {@code program.<world>/composite17.enabled} switches inside {@code #ifdef}.
         */
        String accept(String raw) {
            String trimmed = raw.strip();
            if (trimmed.isEmpty()) return null;
            if (trimmed.endsWith("\\")) {
                pending.append(trimmed, 0, trimmed.length() - 1).append(' ');
                return null;
            }
            if (pending.length() == 0) return trimmed;
            String line = pending + trimmed;
            pending.setLength(0);
            return line;
        }

        /** The open continuation as one logical line, at a directive line or the end of the file. */
        String finish() {
            if (pending.length() == 0) return null;
            String line = pending.toString();
            pending.setLength(0);
            return line;
        }
    }

    /**
     * Keys whose values name pack resources, read after macro expansion as Iris reads them. {@code image.}
     * values resolve their size tokens themselves (AdvancedImageDeclarations), keeping the option each size
     * came from.
     */
    private static final List<String> RESOURCE_PREFIXES = List.of(
            "customTexture.", "texture.", "bufferObject.", "indirect.");
    /** The stages a {@code texture.<stage>.<sampler>} may name, as Iris's TextureStage.parse reads them. */
    static final Set<String> TEXTURE_STAGES = Stream.concat(PASS_TEXTURE_STAGES.stream(), Stream.of("gbuffers"))
            .collect(Collectors.toUnmodifiableSet());

    private static PackResourceDeclaration resourceDeclaration(String key, String value) {
        // as in Iris, a value of more than one word is a raw texture: file, type, format, size
        PackResourceKind textureKind = value.trim().split("\\s+").length > 1
                ? PackResourceKind.RAW_TEXTURE : PackResourceKind.PACK_TEXTURE;
        if (key.startsWith("customTexture.")) {
            String sampler = key.substring("customTexture.".length()).trim();
            return sampler.isBlank() ? null
                    : new PackResourceDeclaration(key, "*", sampler, value, textureKind);
        }
        if (key.equals("texture.noise")) {
            return new PackResourceDeclaration(key, "*", "noisetex", value, PackResourceKind.NOISE);
        }
        if (key.startsWith("texture.")) {
            String[] parts = key.split("\\.", 3);
            // Iris warns about and ignores a stage it does not know, such as a program name, shadow or *
            if (parts.length == 3 && TEXTURE_STAGES.contains(parts[1]) && !parts[2].isBlank()) {
                // Iris keeps only the sampler name: `texture.deferred.colortex6.1` declares a second
                // texture for colortex6, told apart from the first by its type
                String sampler = parts[2].split("\\.")[0];
                return sampler.isBlank() ? null
                        : new PackResourceDeclaration(key, parts[1], sampler, value, textureKind);
            }
        }
        return null;
    }
}
