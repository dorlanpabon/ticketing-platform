package com.ticketing.inventory.dynamodb;

import com.ticketing.inventory.dynamodb.utils.PersistenceConstants;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class ItemAttributes {
    private ItemAttributes() {
    }

    public static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    public static AttributeValue n(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }

    public static Map<String, AttributeValue> key(String pk, String sk) {
        return Map.of(PersistenceConstants.PARTITION_KEY, s(pk), PersistenceConstants.SORT_KEY, s(sk));
    }

    public static Map<String, AttributeValue> item(String pk, String sk) {
        return new HashMap<>(key(pk, sk));
    }

    public static String string(Map<String, AttributeValue> item, String name) {
        return item.get(name).s();
    }

    public static long number(Map<String, AttributeValue> item, String name) {
        return Long.parseLong(item.get(name).n());
    }

    public static String time(Instant time) {
        return String.format(Locale.ROOT, PersistenceConstants.ORDERED_NUMBER_FORMAT, time.toEpochMilli());
    }

    public static int shard(String ticketId) {
        return Integer.parseInt(ticketId) % 16;
    }

    public static String partition(String eventId, int shard) {
        return PersistenceConstants.EVENT_PREFIX + eventId + PersistenceConstants.KEY_SEPARATOR + shard;
    }

    public static String ticketKey(String ticketId) {
        return String.format(Locale.ROOT, PersistenceConstants.TICKET_KEY_FORMAT, Integer.parseInt(ticketId));
    }
}
