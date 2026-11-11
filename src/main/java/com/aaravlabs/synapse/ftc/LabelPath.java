package com.aaravlabs.synapse.ftc;

import java.util.ArrayList;
import java.util.List;

/**
 * A parsed gate-label path: a non-empty {@code /}-separated sequence of segments,
 * decoded with JSON-Pointer-style tilde escapes ({@code ~1} is a literal {@code /}
 * inside a segment, {@code ~0} is a literal {@code ~}). The raw text is what callers
 * write; the decoded segments are what matching compares.
 *
 * <p>The path structure <em>is</em> the label hierarchy: {@code write/drive} is a
 * child of {@code write}, and matching is by whole decoded segments, never raw string
 * prefixes. {@code write} covers {@code write} and {@code write/**} — it never
 * accidentally covers {@code writex}, and it does <b>not</b> cover {@code write~1part}
 * (a single segment literally named {@code write/part}).
 *
 * <p>Encoding is canonical: within a segment {@code ~} becomes {@code ~0} first, then
 * {@code /} becomes {@code ~1}. Decoding scans left to right in one pass, so
 * {@code ~01} is the literal {@code ~1} and never {@code /} — decode order matters and
 * is fixed here.
 *
 * <p>Malformed input fails fast with {@link IllegalArgumentException}: empty segments
 * ({@code a//b}, {@code /a}, {@code a/}), a {@code ~} that is not followed by
 * {@code 0} or {@code 1} (including a trailing {@code ~}), or an empty/absent label.
 */
final class LabelPath {

    private final String raw;
    private final String[] segments;

    private LabelPath(String raw, String[] segments) {
        this.raw = raw;
        this.segments = segments;
    }

    /**
     * Parse raw label text into a path.
     *
     * @param raw the label as written, e.g. {@code "write/drive"} or {@code "write~1part"}
     * @return the parsed path
     * @throws IllegalArgumentException if the label is null, empty, or malformed
     */
    static LabelPath parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("label must not be null");
        }
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("label must not be empty");
        }
        List<String> rawSegments = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= raw.length(); i++) {
            if (i == raw.length() || raw.charAt(i) == '/') {
                if (i == start) {
                    throw new IllegalArgumentException(malformed(raw,
                            "empty label segment (leading, trailing, or doubled '/')"));
                }
                rawSegments.add(raw.substring(start, i));
                start = i + 1;
            }
        }
        String[] decoded = new String[rawSegments.size()];
        for (int i = 0; i < decoded.length; i++) {
            decoded[i] = decodeSegment(raw, rawSegments.get(i));
        }
        return new LabelPath(raw, decoded);
    }

    /** Non-throwing {@link #parse}: returns {@code null} instead of raising. */
    static LabelPath tryParse(String raw) {
        try {
            return parse(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String decodeSegment(String raw, String segment) {
        StringBuilder sb = new StringBuilder(segment.length());
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c != '~') {
                sb.append(c);
                continue;
            }
            if (i + 1 >= segment.length()) {
                throw new IllegalArgumentException(malformed(raw,
                        "'~' must be followed by '0' or '1' (escape '~' as '~0', '/' as '~1')"));
            }
            char next = segment.charAt(++i);
            if (next == '0') {
                sb.append('~');
            } else if (next == '1') {
                sb.append('/');
            } else {
                throw new IllegalArgumentException(malformed(raw,
                        "'~' must be followed by '0' or '1', found '~" + next
                                + "' (escape '~' as '~0', '/' as '~1')"));
            }
        }
        return sb.toString();
    }

    private static String malformed(String raw, String why) {
        return "malformed label '" + raw + "': " + why;
    }

    /**
     * Whether this path is {@code other} itself or a path-ancestor of it
     * (whole decoded segments). {@code write} covers {@code write} and
     * {@code write/drive}; {@code write} does not cover {@code writex}.
     */
    boolean covers(LabelPath other) {
        if (segments.length > other.segments.length) {
            return false;
        }
        for (int i = 0; i < segments.length; i++) {
            if (!segments[i].equals(other.segments[i])) {
                return false;
            }
        }
        return true;
    }

    /** The raw text as written by the caller. */
    String raw() {
        return raw;
    }

    /** Decoded segments; treat as immutable. */
    String[] segments() {
        return segments;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LabelPath && raw.equals(((LabelPath) o).raw);
    }

    @Override
    public int hashCode() {
        return raw.hashCode();
    }

    @Override
    public String toString() {
        return raw;
    }
}
