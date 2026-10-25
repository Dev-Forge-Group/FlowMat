package org.myweb.flowmat.domain.catalog.application;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogEquipmentView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.EquipmentSlot;
import org.myweb.flowmat.domain.catalog.application.publicapi.EquipmentWindow;
import org.myweb.flowmat.domain.catalog.domain.entity.Equipment;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.catalog.domain.entity.UnitMaster;
import org.myweb.flowmat.domain.catalog.repository.EquipmentRepository;
import org.myweb.flowmat.domain.catalog.repository.ItemRepository;
import org.myweb.flowmat.domain.catalog.repository.UnitMasterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CatalogQueryImpl implements CatalogQuery {

    private static final String NOT_DELETED = "N";

    private final ItemRepository itemRepository;
    private final UnitMasterRepository unitMasterRepository;
    private final EquipmentRepository equipmentRepository;
    private final EquipmentScheduleService equipmentScheduleService;
    private final EquipmentChangeoverService equipmentChangeoverService;

    @Override
    public Optional<CatalogItemView> findProjectItem(String projectId, String itemId) {
        return itemRepository.findByItemIdAndDeletedYn(itemId, NOT_DELETED)
            .filter(item -> Objects.equals(projectId, item.getProjectId()))
            .map(item -> view(item, units(List.of(item))));
    }

    @Override
    public Optional<CatalogItemView> findActiveItem(String itemId) {
        return itemRepository.findByItemIdAndDeletedYn(itemId, NOT_DELETED)
            .map(item -> view(item, units(List.of(item))));
    }

    @Override
    public boolean isKnownUnitCode(String unitCode) {
        return unitCode != null && unitMasterRepository.findByUnitCodeIgnoreCase(unitCode.trim()).isPresent();
    }

    @Override
    public Optional<String> findUnitType(String unitCode) {
        return unitCode == null ? Optional.empty() : unitMasterRepository.findByUnitCodeIgnoreCase(unitCode).map(UnitMaster::getUnitType);
    }

    @Override
    public Map<String, CatalogItemView> findItems(Collection<String> itemIds) {
        if (itemIds.isEmpty()) {
            // Unlike Map.of(), an empty map answers a lookup of a null id with null, as the collected map does.
            return Collections.emptyMap();
        }
        List<Item> items = itemRepository.findAllById(itemIds);
        Map<String, UnitMaster> units = units(items);
        return items.stream().collect(Collectors.toMap(Item::getItemId, item -> view(item, units)));
    }

    @Override
    public Map<String, CatalogItemView> findActiveItems(Collection<String> itemIds) {
        if (itemIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Item> items = itemRepository.findAllById(itemIds).stream()
            .filter(item -> NOT_DELETED.equals(item.getDeletedYn()))
            .toList();
        Map<String, UnitMaster> units = units(items);
        return items.stream().collect(Collectors.toMap(Item::getItemId, item -> view(item, units)));
    }

    @Override
    public List<CatalogItemView> findProjectItems(String projectId) {
        List<Item> items = itemRepository.findAllByProjectIdAndDeletedYnOrderByCreatedAtAsc(projectId, NOT_DELETED);
        Map<String, UnitMaster> units = units(items);
        return items.stream().map(item -> view(item, units)).toList();
    }

    @Override
    public Optional<CatalogEquipmentView> findProjectEquipment(String projectId, String equipmentId) {
        return equipmentRepository.findByEquipmentIdAndDeletedYn(equipmentId, NOT_DELETED)
            .filter(equipment -> Objects.equals(projectId, equipment.getProjectId()))
            .map(CatalogQueryImpl::equipmentView);
    }

    @Override
    public List<CatalogEquipmentView> findProjectEquipments(String projectId) {
        return equipmentRepository.findAllByProjectIdAndDeletedYnOrderByCreatedAtDesc(projectId, NOT_DELETED).stream()
            .map(CatalogQueryImpl::equipmentView)
            .toList();
    }

    @Override
    public Optional<EquipmentWindow> equipmentWindow(String equipmentId, OffsetDateTime from, OffsetDateTime to) {
        return equipmentRepository.findByEquipmentIdAndDeletedYn(equipmentId, NOT_DELETED)
            .map(equipment -> equipmentScheduleService.window(equipment, from, to))
            .map(window -> new EquipmentWindow(window.calendarSet(), window.availableHours(), window.downtimeHours()));
    }

    private static CatalogEquipmentView equipmentView(Equipment equipment) {
        return new CatalogEquipmentView(equipment.getEquipmentId(), equipment.getProjectId(), equipment.getEquipmentCode(),
            equipment.getEquipmentName(), equipment.getEquipmentStatus(), equipment.getCapacityPerHour(), equipment.getEfficiencyPercent(),
            "Y".equals(equipment.getConcurrentWorkOrdersYn()), equipment.getConcurrentWorkOrdersLimit());
    }

    @Override
    public Optional<EquipmentSlot> earliestSlot(String equipmentId, OffsetDateTime from, long seconds) {
        return equipmentRepository.findByEquipmentIdAndDeletedYn(equipmentId, NOT_DELETED)
            .flatMap(equipment -> equipmentScheduleService.earliestSlot(equipment, from, seconds));
    }

    @Override
    public Optional<List<EquipmentSlot>> availableSlots(String equipmentId, OffsetDateTime from, OffsetDateTime to) {
        return equipmentRepository.findByEquipmentIdAndDeletedYn(equipmentId, NOT_DELETED)
            .map(equipment -> equipmentScheduleService.availableSlots(equipment, from, to));
    }

    @Override
    public OptionalInt changeoverMinutes(String equipmentId, String fromItemId, String toItemId) {
        return equipmentChangeoverService.changeover(equipmentId, fromItemId, toItemId)
            .map(match -> OptionalInt.of(match.minutes()))
            .orElseGet(OptionalInt::empty);
    }

    private Map<String, UnitMaster> units(List<Item> items) {
        List<String> unitIds = items.stream().map(Item::getUnitId).filter(Objects::nonNull).distinct().toList();
        return unitIds.isEmpty() ? Map.of() : unitMasterRepository.findAllById(unitIds).stream()
            .collect(Collectors.toMap(UnitMaster::getUnitId, unit -> unit));
    }

    private static CatalogItemView view(Item item, Map<String, UnitMaster> units) {
        UnitMaster unit = item.getUnitId() == null ? null : units.get(item.getUnitId());
        return new CatalogItemView(item.getItemId(), item.getProjectId(), item.getItemCode(), item.getItemName(),
            item.getUnitId(), unit == null ? null : unit.getUnitCode(), unit == null ? null : unit.getUnitType(), item.getUnitCost(),
            item.getLeadTimeDays(), item.getItemStatus(), item.getLotManageYn(), item.getPurchaseUnit(), item.getConversionRate(),
            item.getSafetyStockQty(), item.getLotReleaseRequiredYn());
    }
}
