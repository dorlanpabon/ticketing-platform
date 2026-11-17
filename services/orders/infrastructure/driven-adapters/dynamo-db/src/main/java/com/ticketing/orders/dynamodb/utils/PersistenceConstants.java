package com.ticketing.orders.dynamodb.utils;

public final class PersistenceConstants {
    public static final String RETRY_DELAY_MILLIS = "delayMs";
    public static final String PARTITION_KEY = "pk";
    public static final String SORT_KEY = "sk";
    public static final String PAYLOAD = "data";
    public static final String METADATA_SORT_KEY = "META";
    public static final String DUE_INDEX = "gsi1";
    public static final String DUE_PARTITION_KEY = "gsi1pk";
    public static final String DUE_SORT_KEY = "gsi1sk";
    public static final String LEASE_UNTIL = "leaseUntil";
    public static final String TTL = "ttl";
    public static final String RECONCILIATION_PREFIX = "RECONCILE#";
    public static final String COMMAND_PREFIX = "COMMAND#";
    public static final String VERSION_ALIAS = "#version";
    public static final String VERSION_VALUE = ":version";
    public static final String DUE_VALUE = ":due";
    public static final String REVIEW_FAILURES_ATTRIBUTE = "reviewFailures";
    public static final String REVIEW_VERSION_MATCHES = "#version = :version AND attribute_exists(gsi1pk)";
    public static final String ORDER_PREFIX = "ORDER#";
    public static final String VERSION_ATTRIBUTE = "version";
    public static final String ROW_MUST_NOT_EXIST = "attribute_not_exists(pk)";
    public static final String OUTBOX_PREFIX = "OUTBOX#";
    public static final String IDEMPOTENCY_PREFIX = "IDEMP#";
    public static final String FINGERPRINT_ATTRIBUTE = "fingerprint";
    public static final String ENTITY_ID_ATTRIBUTE = "entityId";
    public static final String PURCHASE_MESSAGE_ID = "purchaseMessageId";
    public static final String MESSAGE_ID_VALUE = ":messageId";
    public static final String FINGERPRINT_VALUE = ":fingerprint";
    public static final String ENTITY_ID_VALUE = ":entityId";
    public static final String UNLINKED_SUBMISSION = "attribute_exists(pk) AND attribute_not_exists(purchaseMessageId) AND fingerprint = :fingerprint AND entityId = :entityId";
    public static final String LINK_PURCHASE_MESSAGE = "SET purchaseMessageId = :messageId";
    public static final String OWNER_VALUE = ":owner";
    public static final String NOW_VALUE = ":now";
    public static final String COORDINATOR_PREFIX = "COORDINATOR#";
    public static final String INBOX_PREFIX = "INBOX#";
    public static final String OUTBOX_COORDINATOR = "OUTBOX";
    public static final String RECONCILIATION_COORDINATOR = "RECONCILIATION";
    public static final String ORDER_ID = "orderId";
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    public static final String UNKNOWN_ORDER_MESSAGE = "UNKNOWN_ORDER_MESSAGE";
    public static final String TIMESTAMP_FORMAT = "%016d";
    public static final String PARTITION_VALUE = ":partition";
    public static final String UNTIL_VALUE = ":until";
    public static final String OLD_VERSION_VALUE = ":old";
    public static final String INCREMENT_VALUE = ":one";
    public static final String DUE_QUERY = "gsi1pk = :partition AND gsi1sk <= :now";
    public static final String OUTBOX_LEASE_EXPIRED = "attribute_exists(pk) AND leaseUntil < :now";
    public static final String ACQUIRE_OUTBOX_LEASE = "SET leaseUntil = :until, leaseOwner = :owner, gsi1sk = :due";
    public static final String RELEASE_OUTBOX_LEASE = "SET leaseUntil = :until, gsi1sk = :due REMOVE leaseOwner";
    public static final String LEASE_OWNED = "leaseOwner = :owner";
    public static final long OUTBOX_LEASE_MILLIS = 30_000;
    public static final String COORDINATOR_AVAILABLE = "attribute_not_exists(pk) OR leaseUntil <= :now OR leaseOwner = :owner";
    public static final String ACQUIRE_COORDINATOR_LEASE = "SET leaseUntil = :until, leaseOwner = :owner";
    public static final String PROJECTION_VERSION_MATCHES = "#version = :old";
    public static final String REVIEW_SUCCEEDED = "SET gsi1sk = :due REMOVE reviewFailures";
    public static final String REVIEW_FAILED = "SET gsi1sk = :due ADD reviewFailures :one";
    public static final String TRANSACTION_CONFLICT = "TransactionConflict";
    public static final String THROUGHPUT_EXCEEDED = "ProvisionedThroughputExceeded";
    public static final String THROTTLING_ERROR = "ThrottlingError";
    public static final String TABLE_SCAN_DISABLED = "Use a bounded indexed query; table scans are disabled";

    private PersistenceConstants() {
    }
}
