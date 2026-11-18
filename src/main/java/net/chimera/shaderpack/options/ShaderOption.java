// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack.options;

import java.util.List;
import java.util.Optional;

/**
 * An option a pack's source declares: a toggle, switched by commenting out a {@code #define} or by a
 * {@code const bool}, or a choice among the values a line comment lists.
 */
public sealed interface ShaderOption permits ShaderOption.Toggle, ShaderOption.Choice {
    /** The declaration form: a {@code #define} line or a {@code const} declaration. */
    enum Kind { DEFINE, CONST }

    String name();

    Kind kind();

    /** The text the pack wrote beside the option, without any value list. */
    Optional<String> comment();

    /** The default as the source writes it: {@code true} or {@code false} for a toggle. */
    String defaultText();

    record Toggle(String name, Kind kind, Optional<String> comment, boolean defaultValue) implements ShaderOption {
        public Toggle {
            comment = present(comment);
        }

        @Override
        public String defaultText() {
            return Boolean.toString(defaultValue);
        }
    }

    /** A choice; {@code values} keeps the written order, may repeat a value or hold an empty one. */
    record Choice(String name, Kind kind, Optional<String> comment, String defaultValue, List<String> values)
            implements ShaderOption {
        public Choice {
            comment = present(comment);
            values = List.copyOf(values);
        }

        @Override
        public String defaultText() {
            return defaultValue;
        }
    }

    private static Optional<String> present(Optional<String> comment) {
        return comment == null ? Optional.empty() : comment.filter(text -> !text.isEmpty());
    }
}
