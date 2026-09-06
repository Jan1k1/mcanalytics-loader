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
}
