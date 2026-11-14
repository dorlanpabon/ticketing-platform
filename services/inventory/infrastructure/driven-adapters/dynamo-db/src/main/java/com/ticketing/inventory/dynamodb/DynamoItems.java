package com.ticketing.inventory.dynamodb;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public final class DynamoItems {
    private DynamoItems() { }
    public static AttributeValue s(String value) { return AttributeValue.builder().s(value).build(); }
    public static AttributeValue n(long value) { return AttributeValue.builder().n(Long.toString(value)).build(); }
    public static Map<String, AttributeValue> key(String pk, String sk) { return Map.of("pk", s(pk), "sk", s(sk)); }
    public static Map<String, AttributeValue> item(String pk, String sk) { return new HashMap<>(key(pk, sk)); }
    public static String string(Map<String, AttributeValue> item, String name) { return item.get(name).s(); }
    public static long number(Map<String, AttributeValue> item, String name) { return Long.parseLong(item.get(name).n()); }
    public static String time(Instant time) { return String.format(java.util.Locale.ROOT, "%020d", time.toEpochMilli()); }
    public static int shard(String ticketId) { return Integer.parseInt(ticketId) % 16; }
    public static String partition(String eventId, int shard) { return "EVENT#" + eventId + "#" + shard; }
    public static String ticketKey(String ticketId) { return String.format(java.util.Locale.ROOT, "TICKET#%06d", Integer.parseInt(ticketId)); }
}
