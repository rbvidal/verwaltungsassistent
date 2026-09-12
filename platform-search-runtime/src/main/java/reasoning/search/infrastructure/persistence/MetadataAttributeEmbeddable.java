package reasoning.search.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.Objects;

/** Embeddable key-value pair for chunk metadata attributes. */
@Embeddable
public class MetadataAttributeEmbeddable {

    @Column(name = "attr_key", nullable = false)
    private String key;

    @Column(name = "attr_value")
    private String value;

    protected MetadataAttributeEmbeddable() {
    }

    public MetadataAttributeEmbeddable(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MetadataAttributeEmbeddable that)) {
            return false;
        }
        return Objects.equals(key, that.key) && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, value);
    }
}
