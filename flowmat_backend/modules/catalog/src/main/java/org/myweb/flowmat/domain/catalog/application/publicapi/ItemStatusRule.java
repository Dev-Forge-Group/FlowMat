package org.myweb.flowmat.domain.catalog.application.publicapi;

import java.util.List;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

/**
 * What an item's status allows (docs/domain/item-status.md). Stored lower-case in item.item_status: {@code active},
 * {@code inactive} (not in use for now) or {@code discontinued} (being phased out). A soft-deleted item is marked
 * {@code deleted}, which is not a status anyone sets.
 */
public final class ItemStatusRule {

    public static final String ACTIVE = "active";
    public static final List<String> STATUSES = List.of(ACTIVE, "inactive", "discontinued");

    private ItemStatusRule() {
    }

    /** The status as stored, or null when the value is none of {@link #STATUSES}. */
    public static String normalize(String status) {
        String value = status == null ? "" : status.trim().toLowerCase();
        return STATUSES.contains(value) ? value : null;
    }

    /** 400 unless the value is one of {@link #STATUSES}. */
    public static String requireKnown(String status) {
        String value = normalize(status);
        if (value == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "Status '" + (status == null ? "" : status.trim()) + "' is not one of: " + String.join(", ", STATUSES) + ".");
        }
        return value;
    }

    /** An item saved before statuses were checked may have none; it counts as active. */
    public static boolean isActive(Item item) {
        return isActive(item.getItemStatus());
    }

    /** {@link #isActive(Item)} for an item's status as read through a view. */
    public static boolean isActive(String itemStatus) {
        return itemStatus == null || ACTIVE.equalsIgnoreCase(itemStatus.trim());
    }

    /**
     * Refuses to bring new stock in or plan new use of an item that is not active (409). Issuing, moving, counting and
     * using up its stock stay open, so a phased-out item can run out.
     *
     * @param action what was refused, e.g. "receive stock"
     */
    public static void requireActive(Item item, String action) {
        if (!isActive(item)) {
            throw new BusinessException(ErrorCode.CONFLICT, refusal(item, action));
        }
    }

    /** {@link #requireActive(Item, String)} for an item's code and status as read through a view. */
    public static void requireActive(String itemCode, String itemStatus, String action) {
        if (!isActive(itemStatus)) {
            throw new BusinessException(ErrorCode.CONFLICT, refusal(itemCode, itemStatus, action));
        }
    }

    /** The refusal message, for checks that collect problems instead of throwing. */
    public static String refusal(Item item, String action) {
        return refusal(item.getItemCode(), item.getItemStatus(), action);
    }

    /** {@link #refusal(Item, String)} for an item's code and status as read through a view. */
    public static String refusal(String itemCode, String itemStatus, String action) {
        return itemCode + " is " + itemStatus.trim().toLowerCase() + "; set it back to active to " + action + ".";
    }
}
