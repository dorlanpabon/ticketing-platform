package com.ticketing.inventory.dynamodb;

import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import static com.ticketing.inventory.dynamodb.DynamoItems.*;

public record ModelEntity(String partitionKey, String sortKey, String data) {
    public Map<String, AttributeValue> attributes() {
        var attributes = item(partitionKey, sortKey);
        attributes.put("data", s(data));
        return attributes;
    }

    public static ModelEntity fromAttributes(Map<String, AttributeValue> attributes) {
        return new ModelEntity(string(attributes, "pk"), string(attributes, "sk"), string(attributes, "data"));
    }
}
