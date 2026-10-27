package net.es.nsi.dds.lib.client;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Immutable target policy for bulk cleanup of a caller-selected DDS service.
 * Accepted deletion references identify one subscription at that service's
 * origin and collection path. Relative paths append to the service base path,
 * including references with a leading slash. Validation decodes UTF-8 path
 * escapes once, rejects ambiguous path syntax, then constructs an ASCII URI
 * from the trusted origin and validated path rather than dispatching the peer's
 * original text. UTF-8 encoding preserves the validated Unicode code-point
 * sequence without normalization, keeping normalization-distinct paths distinct.
 * This policy neither resolves DNS aliases nor authorizes the resource operation;
 * callers must separately suppress redirects.
 */
final class SubscriptionTargetPolicy {
    private final URI base;
    private final String basePath;

    /**
     * Captures an HTTP(S) service origin and its deployment path prefix.
     *
     * @param baseURL service URI without userinfo, query, fragment, or ambiguous path
     * @throws IllegalArgumentException if the service URI is unsupported
     */
    SubscriptionTargetPolicy(String baseURL) {
        base = parse(baseURL);
        requireHttpOrigin(base);
        basePath = decode(base.getRawPath());
        validatePath(basePath);
    }

    /**
     * Returns the canonical collection URI before adding the requester query.
     *
     * @return immutable ASCII URI under the captured service path
     */
    URI listTarget() {
        return canonical(append(basePath, "subscriptions"));
    }

    /**
     * Validates a remote link against its case-sensitive subscription identity.
     * Neither input is modified; the returned URI uses the captured origin.
     *
     * @param id nonempty, unambiguous subscription path segment; not limited to UUIDs
     * @param href absolute same-origin or service-relative subscription reference
     * @return canonical ASCII URI for this service's subscription resource
     * @throws IllegalArgumentException if the identity, origin, or resource path
     *                                  is invalid or does not match
     */
    URI deletionTarget(String id, String href) {
        validateSegment(id);
        URI reference = parse(href);
        if (reference.isOpaque() || reference.getRawQuery() != null || reference.getRawFragment() != null
                || reference.getRawUserInfo() != null || href.startsWith("//") || href.indexOf('\\') >= 0) {
            throw invalid();
        }
        String targetPath;
        if (reference.isAbsolute()) {
            requireHttpOrigin(reference);
            if (!base.getScheme().equalsIgnoreCase(reference.getScheme())
                    || !base.getHost().equalsIgnoreCase(reference.getHost())
                    || port(base) != port(reference)) {
                throw invalid();
            }
            targetPath = decode(reference.getRawPath());
        } else {
            if (reference.getRawAuthority() != null) {
                throw invalid();
            }
            String relativePath = decode(reference.getRawPath());
            if (relativePath.startsWith("/")) {
                relativePath = relativePath.substring(1);
            }
            targetPath = append(basePath, relativePath);
        }
        validatePath(targetPath);
        String expected = append(basePath, "subscriptions/" + id);
        if (!expected.equals(targetPath)) {
            throw invalid();
        }
        return canonical(expected);
    }

    // Rebuild the URI from validated components rather than reusing peer text.
    private URI canonical(String path) {
        try {
            URI components = new URI(base.getScheme(), null, base.getHost(), base.getPort(), path, null, null);
            ByteBuffer utf8 = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(components.toString()));
            StringBuilder ascii = new StringBuilder();
            String hex = "0123456789ABCDEF";
            while (utf8.hasRemaining()) {
                int value = utf8.get() & 0xff;
                if (value < 128) {
                    ascii.append((char) value);
                } else {
                    ascii.append('%').append(hex.charAt(value >>> 4)).append(hex.charAt(value & 0xf));
                }
            }
            URI target = URI.create(ascii.toString());
            if (!path.equals(target.getPath())) {
                throw invalid();
            }
            return target;
        } catch (URISyntaxException | CharacterCodingException ex) {
            throw invalid();
        }
    }

    private static URI parse(String value) {
        if (value == null || value.isBlank() || value.indexOf('\\') >= 0) {
            throw invalid();
        }
        try {
            return new URI(value);
        } catch (URISyntaxException ex) {
            throw invalid();
        }
    }

    private static void requireHttpOrigin(URI uri) {
        String scheme = uri.getScheme();
        if (uri.isOpaque() || scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getPort() > 65535) {
            throw invalid();
        }
    }

    private static int port(URI uri) {
        return uri.getPort() == -1 ? (uri.getScheme().equalsIgnoreCase("https") ? 443 : 80) : uri.getPort();
    }

    private static String append(String path, String suffix) {
        return (path.endsWith("/") ? path : path + "/") + suffix;
    }

    private static void validatePath(String path) {
        if (!path.isEmpty() && !path.startsWith("/")) {
            throw invalid();
        }
        String[] segments = path.split("/", -1);
        for (int i = 1; i < segments.length; i++) {
            if (segments[i].isEmpty() && i == segments.length - 1) {
                continue;
            }
            validateSegment(segments[i]);
        }
    }

    private static void validateSegment(String segment) {
        if (segment == null || segment.isBlank() || segment.equals(".") || segment.equals("..")) {
            throw invalid();
        }
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)
                    || "/\\;?#%".indexOf(c) >= 0) {
                throw invalid();
            }
        }
    }

    // Decode escapes once; a surviving percent sign is rejected by segment validation.
    private static String decode(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder decoded = new StringBuilder();
        for (int i = 0; i < raw.length();) {
            if (raw.charAt(i) != '%') {
                decoded.append(raw.charAt(i++));
                continue;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (i < raw.length() && raw.charAt(i) == '%') {
                int value = Integer.parseInt(raw.substring(i + 1, i + 3), 16);
                if (value == 47 || value == 92) {
                    throw invalid();
                }
                bytes.write(value);
                i += 3;
            }
            try {
                decoded.append(StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes.toByteArray())));
            } catch (CharacterCodingException ex) {
                throw invalid();
            }
        }
        return decoded.toString();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid bulk subscription target");
    }
}
