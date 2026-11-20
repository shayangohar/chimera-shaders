// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The std140 block a translated compute stage reads its value uniforms from, each member at an explicit offset.
 * A vec3 takes four whole words, since dispatch leaves the fourth empty where std140 would pack a scalar.
 */
public final class ComputeValueBlock {
    private static final Map<String, Integer> MATRIX_SIZES = Map.of("mat2", 32, "mat3", 48, "mat4", 64);
    private static final List<String> FOUR_WORD_VECTORS =
            List.of("vec3", "vec4", "ivec3", "ivec4", "uvec3", "uvec4", "bvec3", "bvec4");
    private static final Set<String> TWO_WORD_VECTORS = Set.of("vec2", "ivec2", "uvec2", "bvec2");

    private ComputeValueBlock() {}

    public record Value(String name, String type, int offset, int size) {}

    /** In the map's order. */
    public static List<Value> layout(Map<String, String> typesByName) {
        int offset = 0;
        List<Value> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : typesByName.entrySet()) {
            int size = size(entry.getValue());
            offset = align(offset, alignment(entry.getValue()));
            result.add(new Value(entry.getKey(), entry.getValue(), offset, size));
            offset += size;
        }
        return List.copyOf(result);
    }

    /** Rounded to 16. */
    public static int bufferSize(List<Value> values) {
        if (values == null || values.isEmpty()) return 0;
        Value last = values.get(values.size() - 1);
        return align(last.offset() + last.size(), 16);
    }

    public static String declaration(String blockName, List<Value> values, int binding) {
        if (values == null || values.isEmpty()) return "";
        StringBuilder block = new StringBuilder("layout(std140, binding = ").append(binding)
                .append(") uniform ").append(blockName).append(" {\n");
        for (Value value : values) {
            block.append("    layout(offset = ").append(value.offset()).append(") ")
                    .append(value.type()).append(' ').append(value.name()).append(";\n");
        }
        return block.append("};\n").toString();
    }

    private static int alignment(String type) {
        if (isFourWordVector(type) || type.startsWith("mat")) return 16;
        if (type.endsWith("vec2")) return 8;
        return 4;
    }

    private static int size(String type) {
        Integer matrixSize = MATRIX_SIZES.get(type);
        if (matrixSize != null) return matrixSize;
        if (isFourWordVector(type)) return 16;
        if (TWO_WORD_VECTORS.contains(type)) return 8;
        return 4;
    }

    private static boolean isFourWordVector(String type) {
        return FOUR_WORD_VECTORS.stream().anyMatch(type::startsWith);
    }

    private static int align(int value, int alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }
}
