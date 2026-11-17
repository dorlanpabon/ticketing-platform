package com.ticketing.inventory.dynamodb;

import com.ticketing.inventory.dynamodb.utils.PersistenceConstants;
import static com.ticketing.inventory.dynamodb.ItemAttributes.item;
import static com.ticketing.inventory.dynamodb.ItemAttributes.s;
import static com.ticketing.inventory.dynamodb.ItemAttributes.string;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Map;

public record TableEntity(String partitionKey, String sortKey, String data) {

    public Map<String, AttributeValue> attributes() {
        var attributes = item(partitionKey, sortKey);
        attributes.put(PersistenceConstants.DATA, s(data));
        return attributes;
    }

    public static TableEntity fromAttributes(Map<String, AttributeValue> attributes) {
        return new TableEntity(string(attributes, PersistenceConstants.PARTITION_KEY),
            string(attributes, PersistenceConstants.SORT_KEY), string(attributes, PersistenceConstants.DATA));
    }
}
