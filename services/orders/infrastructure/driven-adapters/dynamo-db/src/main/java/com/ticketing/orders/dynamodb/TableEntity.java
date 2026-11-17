package com.ticketing.orders.dynamodb;

import com.ticketing.orders.dynamodb.utils.PersistenceConstants;

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

    private String partitionKey;
    private String payloadJson;
    private String sortKey = PersistenceConstants.METADATA_SORT_KEY;
    private String indexPartition;
    private String indexSort;

    public TableEntity() {
    }

    public TableEntity(String partitionKey, String payloadJson) {
        this.partitionKey = partitionKey;
        this.payloadJson = payloadJson;
    }

    @DynamoDbPartitionKey
    @DynamoDbAttribute(PersistenceConstants.PARTITION_KEY)
    public String getPartitionKey() {
        return partitionKey;
    }

    public void setPartitionKey(String partitionKey) {
        this.partitionKey = partitionKey;
    }

    @DynamoDbAttribute(PersistenceConstants.PAYLOAD)
    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    @DynamoDbSortKey
    @DynamoDbAttribute(PersistenceConstants.SORT_KEY)
    public String getSortKey() {
        return sortKey;
    }

    public void setSortKey(String value) {
        sortKey = value;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = PersistenceConstants.DUE_INDEX)
    @DynamoDbAttribute(PersistenceConstants.DUE_PARTITION_KEY)
    public String getIndexPartition() {
        return indexPartition;
    }

    public void setIndexPartition(String value) {
        indexPartition = value;
    }

    @DynamoDbSecondarySortKey(indexNames = PersistenceConstants.DUE_INDEX)
    @DynamoDbAttribute(PersistenceConstants.DUE_SORT_KEY)
    public String getIndexSort() {
        return indexSort;
    }

    public void setIndexSort(String value) {
        indexSort = value;
    }

    public Map<String, AttributeValue> attributes() {
        return new HashMap<>(Map.of(PersistenceConstants.PARTITION_KEY, AttributeValue.fromS(partitionKey),
            PersistenceConstants.SORT_KEY, AttributeValue.fromS(sortKey), PersistenceConstants.PAYLOAD,
            AttributeValue.fromS(payloadJson)));
    }
}
