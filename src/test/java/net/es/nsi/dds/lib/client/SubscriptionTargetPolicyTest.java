package net.es.nsi.dds.lib.client;

import java.net.URI;
import org.junit.Test;
import static org.junit.Assert.*;

/** Checks canonical bulk-target identity, origin/path restrictions, and exact Unicode preservation. */
public class SubscriptionTargetPolicyTest {
    @Test
    public void acceptsOnlyMatchingOriginScopeAndIdentity() {
        SubscriptionTargetPolicy policy = new SubscriptionTargetPolicy("HTTP://Example.COM:80/dds/");
        assertEquals("/dds/subscriptions/réseau-1", policy.deletionTarget("réseau-1", "subscriptions/r%C3%A9seau-1").getPath());
        assertEquals("/dds/subscriptions/id", policy.deletionTarget("id", "/subscriptions/id").getPath());
        assertEquals("/dds/subscriptions/id", policy.deletionTarget("id", "http://example.com/dds/subscriptions/id").getPath());
        assertEquals(URI.create("https://example.com/subscriptions/a"),
                new SubscriptionTargetPolicy("https://example.com").deletionTarget("a", "https://EXAMPLE.com:443/subscriptions/a"));
    }

    @Test
    public void rejectsAmbiguousOrMismatchedTargets() {
        SubscriptionTargetPolicy policy = new SubscriptionTargetPolicy("https://example.com/dds");
        String[] invalid = {"http://example.com/dds/subscriptions/a", "https://elsewhere/dds/subscriptions/a",
            "https://example.com:444/dds/subscriptions/a", "//example.com/dds/subscriptions/a",
            "https://user@example.com/dds/subscriptions/a", "mailto:a", "subscriptions/a?x=y", "subscriptions/a#x",
            "subscriptions/b", "subscriptions/A", "documents/a", "subscriptions/a/", "subscriptions/a/other",
            "subscriptions/../subscriptions/a", "subscriptions/%2e%2e/a", "subscriptions/a;x=1",
            "subscriptions/a%2fb", "subscriptions/a%5cb", "subscriptions/%2561", "subscriptions/%C0%AF",
            "subscriptions\\a", "", null};
        for (String href : invalid) {
            assertThrows(String.valueOf(href), IllegalArgumentException.class, () -> policy.deletionTarget("a", href));
        }
        for (String id : new String[]{null, "", " ", ".", "..", "a/b", "a%2fb", "a;b", "a\\b"}) {
            assertThrows(String.valueOf(id), IllegalArgumentException.class, () -> policy.deletionTarget(id, "subscriptions/a"));
        }
    }

    @Test
    public void rejectsInvalidServiceBases() {
        for (String base : new String[]{null, "", "relative", "ftp://example.com", "http://user@example.com",
            "http://example.com?x=y", "http://example.com#x", "http://example.com/a/../b",
            "http://example.com/a//b", "http://example.com/a%2fb", "http://example.com:65536"}) {
            assertThrows(String.valueOf(base), IllegalArgumentException.class, () -> new SubscriptionTargetPolicy(base));
        }
    }

    @Test
    public void comparesOriginUsingSchemeHostAndEffectivePort() {
        SubscriptionTargetPolicy http = new SubscriptionTargetPolicy("http://Example.COM/dds");
        assertEquals(URI.create("http://Example.COM/dds/subscriptions/a"),
                http.deletionTarget("a", "HTTP://example.com:80/dds/subscriptions/a"));
        SubscriptionTargetPolicy https = new SubscriptionTargetPolicy("https://example.com:443/dds/");
        assertEquals(URI.create("https://example.com:443/dds/subscriptions/a"),
                https.deletionTarget("a", "https://EXAMPLE.COM/dds/subscriptions/a"));
        for (String differentOrigin : new String[]{"https://example.com/dds/subscriptions/a",
            "http://example.com:443/dds/subscriptions/a", "http://example.com:81/dds/subscriptions/a",
            "http://example.com.evil/dds/subscriptions/a", "http://example.com./dds/subscriptions/a"}) {
            assertThrows(differentOrigin, IllegalArgumentException.class,
                    () -> http.deletionTarget("a", differentOrigin));
        }
    }

    @Test
    public void preservesServicePrefixAndCanonicalizesAcceptedEncoding() {
        for (String base : new String[]{"https://example.com/dds", "https://example.com/dds/", "https://example.com/%64ds"}) {
            SubscriptionTargetPolicy policy = new SubscriptionTargetPolicy(base);
            assertEquals("/dds/subscriptions", policy.listTarget().getRawPath());
            for (String href : new String[]{"subscriptions/%61", "/subscriptions/a", "https://example.com/dds/subscriptions/a"}) {
                assertEquals("/dds/subscriptions/a", policy.deletionTarget("a", href).getRawPath());
            }
            for (String href : new String[]{"/dds/subscriptions/a", "dds/subscriptions/a",
                "https://example.com/subscriptions/a", "https://example.com/dds-other/subscriptions/a",
                "https://example.com/dds/subscriptions/a/child"}) {
                assertThrows(href, IllegalArgumentException.class, () -> policy.deletionTarget("a", href));
            }
        }
    }

    @Test
    public void acceptsNonUuidAndUnicodeIdsWithoutChangingIdentity() {
        SubscriptionTargetPolicy policy = new SubscriptionTargetPolicy("https://example.com");
        String[][] accepted = {{"réseau", "r%C3%A9seau"}, {"東京", "%E6%9D%B1%E4%BA%AC"},
            {"simple-id", "simple-id"}, {"node@site:tag", "node%40site%3Atag"}, {"a+b", "a%2Bb"}};
        for (String[] target : accepted) {
            URI uri = policy.deletionTarget(target[0], "subscriptions/" + target[1]);
            assertEquals("/subscriptions/" + target[0], uri.getPath());
            assertNull(uri.getQuery());
            assertNull(uri.getFragment());
            assertNull(uri.getUserInfo());
        }
        assertThrows(IllegalArgumentException.class, () -> policy.deletionTarget("réseau", "subscriptions/R%C3%A9seau"));
    }

    @Test
    public void rejectsEncodedSyntaxAndMalformedUtf8BeforeCanonicalization() {
        SubscriptionTargetPolicy policy = new SubscriptionTargetPolicy("https://example.com/dds");
        for (String href : new String[]{"subscriptions/a?", "subscriptions/a#", "subscriptions/%61%3Fx=y",
            "subscriptions/%61%23f", "subscriptions/a%3Bb", "subscriptions/a%253Bb", "subscriptions/%252e%252e/a",
            "subscriptions/%2Fa", "subscriptions/%2fa", "subscriptions/%5Ca", "subscriptions/%5ca",
            "subscriptions/a%00", "subscriptions/a%09", "subscriptions/a%20", "subscriptions/%ED%A0%80",
            "subscriptions/%F4%90%80%80", "subscriptions/%80", "subscriptions/%E2%82", "subscriptions/%FF",
            "subscriptions/%", "subscriptions/%0", "subscriptions/%GG", "subscriptions//a",
            "subscriptions/./a", "subscriptions/%2e/a", "https://example.com@evil/dds/subscriptions/a",
            "https://example.com:443@evil/dds/subscriptions/a"}) {
            assertThrows(href, IllegalArgumentException.class, () -> policy.deletionTarget("a", href));
        }
    }

    @Test
    public void canonicalTargetPreservesDistinctUnicodeNormalizationForms() {
        SubscriptionTargetPolicy policy = new SubscriptionTargetPolicy("https://example.com/dds");
        String[][] identities = {{"e\u0301", "e%CC%81"}, {"\u00e9", "%C3%A9"},
            {"ascii-e\u0301-\u6771\u4eac", "ascii-e%CC%81-%E6%9D%B1%E4%BA%AC"}};
        for (String[] identity : identities) {
            URI target = policy.deletionTarget(identity[0], "subscriptions/" + identity[1]);
            assertEquals("Canonicalization must retain the validated code points",
                    "/dds/subscriptions/" + identity[0], target.getPath());
            assertEquals("/dds/subscriptions/" + identity[1], target.getRawPath());
        }
        assertThrows(IllegalArgumentException.class,
                () -> policy.deletionTarget("e\u0301", "subscriptions/%C3%A9"));
        assertThrows(IllegalArgumentException.class,
                () -> policy.deletionTarget("\u00e9", "subscriptions/e%CC%81"));
    }

    @Test
    public void canonicalListAndDeleteKeepDecomposedServicePrefix() {
        for (String prefix : new String[]{"cafe\u0301", "cafe%CC%81"}) {
            SubscriptionTargetPolicy policy = new SubscriptionTargetPolicy("https://example.com/" + prefix);
            assertEquals("/cafe\u0301/subscriptions", policy.listTarget().getPath());
            assertEquals("/cafe%CC%81/subscriptions", policy.listTarget().getRawPath());
            URI deletion = policy.deletionTarget("a", "subscriptions/a");
            assertEquals("/cafe\u0301/subscriptions/a", deletion.getPath());
            assertEquals("/cafe%CC%81/subscriptions/a", deletion.getRawPath());
        }
    }
}
