package com.ticketing.orders.dynamodb;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

/* Enhanced DynamoDB annotations are incompatible with Lombok #1932
         https://github.com/aws/aws-sdk-java-v2/issues/1932*/
@DynamoDbBean
public class ModelEntity {

    private String id;
    private String atr1;
    private String sortKey = "META";
    private String indexPartition;
    private String indexSort;

    public ModelEntity() {
    }

    public ModelEntity(String id, String atr1) {
        this.id = id;
        this.atr1 = atr1;
    }

    @DynamoDbPartitionKey
    @DynamoDbAttribute("pk")
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    @DynamoDbAttribute("data")
    public String getAtr1() {
        return atr1;
    }

    public void setAtr1(String atr1) {
        this.atr1 = atr1;
    }
    @DynamoDbSortKey
    @DynamoDbAttribute("sk")
    public String getSortKey() { return sortKey; }
    public void setSortKey(String value) { sortKey = value; }

    @DynamoDbSecondaryPartitionKey(indexNames = "gsi1")
    @DynamoDbAttribute("gsi1pk")
    public String getIndexPartition() { return indexPartition; }
    public void setIndexPartition(String value) { indexPartition = value; }

    @DynamoDbSecondarySortKey(indexNames = "gsi1")
    @DynamoDbAttribute("gsi1sk")
    public String getIndexSort() { return indexSort; }
    public void setIndexSort(String value) { indexSort = value; }

    public java.util.Map<String,software.amazon.awssdk.services.dynamodb.model.AttributeValue> attributes() {
        return new java.util.HashMap<>(java.util.Map.of(
                "pk",software.amazon.awssdk.services.dynamodb.model.AttributeValue.fromS(id),
                "sk",software.amazon.awssdk.services.dynamodb.model.AttributeValue.fromS(sortKey),
                "data",software.amazon.awssdk.services.dynamodb.model.AttributeValue.fromS(atr1)));
    }
}
