package org.myweb.flowmat.domain.catalog.application.publicapi;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Catalog reads for other bounded contexts, which use this instead of the catalog repositories
 * (docs/architecture/adr/ADR-002-module-dependency.md). Add an operation only when a caller needs it.
 */
public interface CatalogQuery {

    /** The item if it exists, is not deleted and belongs to the project. */
    Optional<CatalogItemView> findProjectItem(String projectId, String itemId);

    /**
     * The item if it exists and is not deleted, whatever its project; for callers that answer "no such item" and
     * "item of another project" differently.
     */
    Optional<CatalogItemView> findActiveItem(String itemId);

    /**
     * Keeps the project's nondeleted item stable until the caller's transaction ends. The shared lock permits other
     * receipts, but an ongoing item write is a conflict rather than a wait. Requires an existing write transaction;
     * callers check access before taking the lock.
     */
    Optional<CatalogItemView> lockProjectItemForStock(String projectId, String itemId);

    /**
     * Keeps a project's nondeleted item stable while the caller saves a reference to it. Shares the row with other
     * references and receipts; an ongoing item write is a conflict without waiting. Requires the caller's write
     * transaction and prior project access check, and returns the freshly loaded item for the caller's validation.
     */
    Optional<CatalogItemView> lockProjectItemForReference(String projectId, String itemId);

    /** Whether a unit with this code exists, ignoring case. */
    boolean isKnownUnitCode(String unitCode);

    /** The type (e.g. mass, count) of the unit with this code, ignoring case; empty when no unit has the code. */
    Optional<String> findUnitType(String unitCode);

    /** Items by id, deleted ones included so old references still show a code; ids that do not exist are left out. */
    Map<String, CatalogItemView> findItems(Collection<String> itemIds);

    /** Items by id that are not deleted, whatever their project; ids that do not exist or are deleted are left out. */
    Map<String, CatalogItemView> findActiveItems(Collection<String> itemIds);

    /** The project's items that are not deleted, oldest first. */
    List<CatalogItemView> findProjectItems(String projectId);

    /** The equipment if it exists, is not deleted and belongs to the project. */
    Optional<CatalogEquipmentView> findProjectEquipment(String projectId, String equipmentId);

    /** The project's equipment that is not deleted, newest first. */
    List<CatalogEquipmentView> findProjectEquipments(String projectId);

    /**
     * The equipment's working time in [from, to), with no access check (callers check it); empty when the equipment is
     * gone. The caller keeps the window at most 366 days long.
     */
    Optional<EquipmentWindow> equipmentWindow(String equipmentId, OffsetDateTime from, OffsetDateTime to);

    /**
     * The earliest stretch from {@code from} that holds {@code seconds} of the equipment's available time: its shifts less
     * holidays and downtime, or all the time without a calendar (docs/domain/equipment-schedule.md "계획 기간 제안").
     * Empty when the equipment is gone or the 366 days from {@code from} do not hold that much.
     */
    Optional<EquipmentSlot> earliestSlot(String equipmentId, OffsetDateTime from, long seconds);

    /**
     * The equipment's available time in [from, to) as stretches, earliest first and none overlapping: what
     * {@link #earliestSlot} counts. Empty when the equipment is gone. The caller keeps the window at most 366 days long.
     */
    Optional<List<EquipmentSlot>> availableSlots(String equipmentId, OffsetDateTime from, OffsetDateTime to);

    /** Minutes to change the equipment over from one item to the next, when a changeover rule applies. */
    OptionalInt changeoverMinutes(String equipmentId, String fromItemId, String toItemId);
}
