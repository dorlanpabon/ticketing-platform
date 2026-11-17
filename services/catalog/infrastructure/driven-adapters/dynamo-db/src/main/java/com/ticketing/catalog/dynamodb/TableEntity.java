package com.ticketing.catalog.dynamodb;

import com.ticketing.catalog.dynamodb.utils.PersistenceConstants;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.HashMap;
import java.util.Map;

/* Enhanced DynamoDB annotations are incompatible with Lombok #1932
https://github.com/aws/aws-sdk-java-v2/issues/1932*/
@DynamoDbBean
public class TableEntity {

    private String id;
    private String payload;
    private String sortKey = PersistenceConstants.METADATA_SORT_KEY;
    private String indexPartitionKey;
    private String indexSortKey;

    public TableEntity() {
    }

    public TableEntity(String id, String payload) {
        this.id = id;
        this.payload = payload;
    }

    @DynamoDbPartitionKey
    @DynamoDbAttribute(PersistenceConstants.PARTITION_KEY)
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    @DynamoDbAttribute(PersistenceConstants.DATA_ATTRIBUTE)
    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    @DynamoDbSortKey
    @DynamoDbAttribute(PersistenceConstants.SORT_KEY)
    public String getSortKey() {
        return sortKey;
    }

    public void setSortKey(String value) {
        sortKey = value;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = PersistenceConstants.OUTBOX_INDEX_NAME)
    @DynamoDbAttribute(PersistenceConstants.OUTBOX_PARTITION_KEY)
    public String getIndexPartitionKey() {
        return indexPartitionKey;
    }

    public void setIndexPartitionKey(String value) {
        indexPartitionKey = value;
    }

    @DynamoDbSecondarySortKey(indexNames = PersistenceConstants.OUTBOX_INDEX_NAME)
    @DynamoDbAttribute(PersistenceConstants.OUTBOX_SORT_KEY)
    public String getIndexSortKey() {
        return indexSortKey;
    }

    public void setIndexSortKey(String value) {
        indexSortKey = value;
    }

    public Map<String, AttributeValue> attributes() {
        return new HashMap<>(Map.of(PersistenceConstants.PARTITION_KEY, AttributeValue.fromS(id),
            PersistenceConstants.SORT_KEY, AttributeValue.fromS(sortKey), PersistenceConstants.DATA_ATTRIBUTE,
            AttributeValue.fromS(payload)));
    }
}
