package com.ticketing.catalog.dynamodb.utils;

public final class PersistenceConstants {
    public static final String PARTITION_KEY = "pk";
    public static final String SORT_KEY = "sk";
    public static final String DATA_ATTRIBUTE = "data";
    public static final String METADATA_SORT_KEY = "META";
    public static final String OUTBOX_INDEX_NAME = "gsi1";
    public static final String OUTBOX_PARTITION_KEY = "gsi1pk";
    public static final String OUTBOX_SORT_KEY = "gsi1sk";
    public static final String ACTIVE_EVENTS_INDEX_NAME = "gsi2";
    public static final String ACTIVE_EVENTS_PARTITION_KEY = "gsi2pk";
    public static final String ACTIVE_EVENTS_SORT_KEY = "gsi2sk";
    public static final String ACTIVE_EVENTS_PARTITION = "ACTIVE_EVENTS";
    public static final String EVENT_PREFIX = "EVENT#";
    public static final String OUTBOX_PREFIX = "OUTBOX#";
    public static final String IDEMPOTENCY_PREFIX = "IDEMP#";
    public static final String FINGERPRINT = "fingerprint";
    public static final String ENTITY_ID = "entityId";
    public static final String TTL = "ttl";
    public static final String LEASE_UNTIL = "leaseUntil";
    public static final String NOW_PARAMETER = ":now";
    public static final String UNTIL_PARAMETER = ":until";
    public static final String OWNER_PARAMETER = ":owner";
    public static final String ATTRIBUTE_NOT_EXISTS = "attribute_not_exists(pk)";
    public static final String ATTRIBUTE_EXISTS = "attribute_exists(pk)";
    public static final String INBOX_PREFIX = "INBOX#";
    public static final String COORDINATOR_PREFIX = "COORDINATOR#";
    public static final String OUTBOX_COORDINATOR = "OUTBOX";
    public static final String EVENTS_PARAMETER = ":events";
    public static final String PARTITION_PARAMETER = ":partition";
    public static final String DUE_PARAMETER = ":due";
    public static final String ACTIVE_EVENTS_CONDITION = "gsi2pk = :events";
    public static final String DUE_OUTBOX_CONDITION = "gsi1pk = :partition AND gsi1sk <= :now";
    public static final String AVAILABLE_OUTBOX_LEASE = "attribute_exists(pk) AND leaseUntil < :now";
    public static final String CLAIM_OUTBOX_LEASE = "SET leaseUntil = :until, leaseOwner = :owner, gsi1sk = :due";
    public static final String OWNED_LEASE = "leaseOwner = :owner";
    public static final String AVAILABLE_COORDINATOR_LEASE = "attribute_not_exists(pk) OR leaseUntil <= :now OR leaseOwner = :owner";
    public static final String CLAIM_COORDINATOR_LEASE = "SET leaseUntil = :until, leaseOwner = :owner";
    public static final String TIME_SORT_FORMAT = "%016d";
    public static final String KEY_SEPARATOR = "#";
    public static final String CURSOR_SEPARATOR = "\n";
    public static final String INVALID_CURSOR_MESSAGE = "Invalid cursor";
    public static final String SCAN_DISABLED_MESSAGE = "Use a bounded indexed query; table scans are disabled";
    public static final int SHARD_COUNT = 16;

    private PersistenceConstants() {
    }
}
