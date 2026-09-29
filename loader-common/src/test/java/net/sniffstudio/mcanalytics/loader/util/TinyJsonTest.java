package net.sniffstudio.mcanalytics.loader.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TinyJsonTest {

    @Test
    void parsesAndSerializesObject() {
        String json = "{\"connectorToken\":\"mca_live_test123\",\"networkId\":\"net-1\",\"size\":12345,\"active\":true}";
        Map<String, Object> map = TinyJson.parseObject(json);

        assertThat(TinyJson.getString(map, "connectorToken")).isEqualTo("mca_live_test123");
        assertThat(TinyJson.getString(map, "networkId")).isEqualTo("net-1");
        assertThat(TinyJson.getLong(map, "size")).isEqualTo(12345L);
        assertThat(TinyJson.getBoolean(map, "active")).isTrue();

        String serialized = TinyJson.toJson(map);
        Map<String, Object> roundtrip = TinyJson.parseObject(serialized);
        assertThat(roundtrip).isEqualTo(map);
    }

    @Test
    void handlesEscapedStringsAndNestedMaps() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("key", "hello \"world\"\nnewline");
        nested.put("items", List.of("a", "b", "c"));

        String json = TinyJson.toJson(nested);
        Map<String, Object> parsed = TinyJson.parseObject(json);
        assertThat(TinyJson.getString(parsed, "key")).isEqualTo("hello \"world\"\nnewline");
    }

    private static String thrownMessage(String input) {
        try {
            TinyJson.parse(input);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        throw new AssertionError("expected the parse to fail");
    }

    @Test
    void parseErrorsNeverRepeatTheInput() {
        String secret = "mca_live_SECRETTOKEN";
        String[] inputs = {
                secret,
                "[" + secret + "]",
                "{\"a\": " + secret + "}",
                "{" + secret + "}",
                "{\"a\" " + secret + "}",
                "{\"a\":\"" + secret,
                "{\"a\":\"\\u" + "ZZZZ" + secret + "\"}",
                "{\"a\":-" + secret + "}",
                "{\"a\":1e" + secret + "}",
                "{\"a\":t" + secret + "}",
                "{\"a\":n" + secret + "}",
                "\"" + secret + "\"",
        };
        for (String input : inputs) {
            try {
                Object parsed = TinyJson.parse(input);
                if (parsed instanceof String) {
                    // A bare string is valid JSON; the object accessor is what refuses it.
                    assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                            () -> TinyJson.parseObject(input)).getMessage()).doesNotContain("SECRET");
                }
            } catch (IllegalArgumentException e) {
                assertThat(e.getMessage()).doesNotContain("SECRET").doesNotContain(secret);
            }
        }
        assertThat(thrownMessage("-")).doesNotContain("For input string");
    }

    @Test
    void refusesInputAboveTheSizeLimit() {
        String atLimit = "\"" + "a".repeat(TinyJson.MAX_INPUT_LENGTH - 2) + "\"";
        assertThat(TinyJson.parse(atLimit)).isInstanceOf(String.class);

        String tooBig = "\"" + "a".repeat(TinyJson.MAX_INPUT_LENGTH) + "\"";
        assertThat(thrownMessage(tooBig)).isEqualTo("JSON input is too large");
    }

    @Test
    void refusesNestingAboveTheDepthLimit() {
        int max = TinyJson.MAX_DEPTH;
        String ok = "[".repeat(max) + "]".repeat(max);
        assertThat(TinyJson.parse(ok)).isInstanceOf(List.class);

        String tooDeepArrays = "[".repeat(max + 1) + "]".repeat(max + 1);
        assertThat(thrownMessage(tooDeepArrays)).isEqualTo("JSON is nested too deeply");

        String tooDeepObjects = "{\"a\":".repeat(max + 1) + "1" + "}".repeat(max + 1);
        assertThat(thrownMessage(tooDeepObjects)).isEqualTo("JSON is nested too deeply");

        // An input that would overflow the stack if the parser had no limit.
        assertThat(thrownMessage("[".repeat(20_000))).isEqualTo("JSON is nested too deeply");
    }
}
