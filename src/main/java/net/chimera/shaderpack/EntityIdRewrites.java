// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.chimera.shaderpack.GlslLexer.stripComments;

/**
 * Iris's per-vertex entity identities (EntityPatcher), carried from the EntityIds input to later stages in flat
 * varyings.
 */
final class EntityIdRewrites {
    /** In component order, as Iris's iris_Entity holds them. */
    static final List<String> ENTITY_ID_NAMES = List.of("entityId", "blockEntityId", "currentRenderedItemId");

    static final String ENTITY_ID_VARYING = "chimeraEntityId";

    private EntityIdRewrites() {}

    static String entityIdVarying(String name) {
        return switch (name) {
            case "entityId" -> ENTITY_ID_VARYING;
            case "blockEntityId" -> "chimeraBlockEntityId";
            default -> "chimeraRenderedItemId";
        };
    }

    static String entityIdComponent(String name) {
        return switch (name) {
            case "entityId" -> "x";
            case "blockEntityId" -> "y";
            default -> "z";
        };
    }

    static Map<String, String> entityIdTypes(String strippedVertex, String fragmentSource) {
        Map<String, String> types = new LinkedHashMap<>();
        for (String name : ENTITY_ID_NAMES) {
            String type = entityIdentifierType(name, strippedVertex, fragmentSource);
            if (type != null) types.put(name, type);
        }
        return types;
    }

    static String injectEntityIdPrologues(String source, Collection<String> names) {
        String result = source;
        for (String name : names) {
            result = LegacyGlslConverter.injectMainPrologue(result,
                    entityIdVarying(name) + " = EntityIds." + entityIdComponent(name) + ";");
        }
        return result;
    }

    static String entityIdOutputDeclarations(Collection<String> names,
                                             LegacyGlslConverter.TerrainVaryingLayout layout) {
        StringBuilder declarations = new StringBuilder();
        for (String name : names) {
            declarations.append("layout(location = " + layout.location(entityIdVarying(name))
                    + ") flat out uint " + entityIdVarying(name) + ";\n");
        }
        return declarations.toString();
    }

    /**
     * An entity identity is replaced only where it names the global, since a pack may give a parameter or local
     * an identity's name.
     */
    static String replaceInputs(String source, Map<String, String> replacements) {
        Map<String, String> others = new HashMap<>(replacements);
        Map<String, String> identities = new HashMap<>();
        for (String name : ENTITY_ID_NAMES) {
            String value = others.remove(name);
            if (value != null && GlslTokenRewriter.containsIdentifier(source, name)) identities.put(name, value);
        }
        return GlslScopedReferences.replace(GlslTokenRewriter.replaceIdentifiers(source, others), identities);
    }

    static Map<String, String> entityIdReplacements(Collection<String> names,
                                                    LegacyGlslConverter.TerrainVaryingLayout layout) {
        Map<String, String> replacements = new LinkedHashMap<>();
        for (String name : names) {
            replacements.put(name, entityIdExpression(layout, name));
        }
        return replacements;
    }

    static String entityIdentifierType(String name, String vertexSource, String fragmentSource) {
        String vertex = vertexSource == null ? "" : vertexSource;
        String fragment = fragmentSource == null ? "" : stripComments(fragmentSource);
        String declared = EntityDeclarations.declaredEntityIdType(vertex, name);
        if (declared == null) declared = EntityDeclarations.declaredEntityIdType(fragment, name);
        if (declared != null) return declared;
        if (LegacyGlslConverter.containsIdentifier(vertex, name)
                || LegacyGlslConverter.containsIdentifier(fragment, name)) {
            return "int";
        }
        return null;
    }

    static String entityIdExpression(LegacyGlslConverter.TerrainVaryingLayout layout, String name) {
        return entityIdExpression(layout, name, "");
    }

    /** {@code element} is "[0]" for a geometry stage's first vertex. */
    static String entityIdExpression(LegacyGlslConverter.TerrainVaryingLayout layout, String name, String element) {
        String varying = entityIdVarying(name);
        if (layout != null && layout.locations().containsKey(varying)) {
            return "float".equals(layout.types().get(varying))
                    ? "float(" + varying + element + ")"
                    : "int(" + varying + element + ")";
        }
        return "float(EntityIds." + entityIdComponent(name) + ")";
    }

    static String replaceEntityIdReferences(
            String source,
            LegacyGlslConverter.TerrainVaryingLayout layout
    ) {
        if (layout == null) return source;
        Map<String, String> replacements = new HashMap<>();
        for (String name : ENTITY_ID_NAMES) {
            if (layout.locations().containsKey(entityIdVarying(name))) {
                replacements.put(name, entityIdExpression(layout, name));
            }
        }
        // only the global identity; a parameter may share its name, as in replaceInputs
        return replacements.isEmpty() ? source : GlslScopedReferences.replace(source, replacements);
    }
}
