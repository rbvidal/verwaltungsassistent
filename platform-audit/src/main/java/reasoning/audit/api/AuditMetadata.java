package reasoning.audit.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/** Utility class for constructing JSON metadata nodes from maps or returning empty nodes. */
public final class AuditMetadata {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private AuditMetadata() {
    }

    /** Returns an empty JSON object node. */
    public static JsonNode empty() {
        return OBJECT_MAPPER.createObjectNode();
    }

    /** Converts a string map into a JSON node, returning an empty node for null or empty inputs. */
    public static JsonNode from(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return empty();
        }
        return OBJECT_MAPPER.valueToTree(values);
    }
}
