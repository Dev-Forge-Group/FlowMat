package org.myweb.flowmat.domain.catalog.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.api.dto.request.EquipmentChangeoverRequest;
import org.myweb.flowmat.domain.catalog.api.dto.request.EquipmentChangeoverUpdateRequest;
import org.myweb.flowmat.domain.catalog.api.dto.response.EquipmentChangeoverResponse;
import org.myweb.flowmat.domain.catalog.domain.entity.Equipment;
import org.myweb.flowmat.domain.catalog.domain.entity.EquipmentChangeover;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.catalog.repository.EquipmentChangeoverRepository;
import org.myweb.flowmat.domain.catalog.repository.EquipmentRepository;
import org.myweb.flowmat.domain.catalog.repository.ItemRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changeover times on equipment (docs/domain/equipment-changeover.md). The exact pair wins, then a configured setup-attribute rule, then
 * from the item to any, then from any to the item, then any to any. Making the same item again needs no changeover
 * unless the exact pair is set.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EquipmentChangeoverService {

    /** The longest changeover, a week. */
    static final int LONGEST_MINUTES = 7 * 24 * 60;
    private static final String NOT_DELETED = "N";

    private final EquipmentRepository equipmentRepository;
    private final EquipmentChangeoverRepository changeoverRepository;
    private final ItemRepository itemRepository;
    private final ProjectAccessService projectAccessService;
    private final IdGenerator idGenerator;
    private final EntityManager entities;
    private final EquipmentSetupChangeoverService setupChangeovers;

    /** The rule that applies and its time. */
    public record Match(int minutes, String changeoverId) {
    }

    public List<EquipmentChangeoverResponse> list(String equipmentId) {
        Equipment equipment = findEquipment(equipmentId);
        projectAccessService.requireProjectReadAccess(equipment.getProjectId());
        return responses(equipment);
    }

    @Transactional
    public List<EquipmentChangeoverResponse> add(String equipmentId, EquipmentChangeoverRequest request) {
        Equipment equipment = findEquipmentForWrite(equipmentId);
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "minutes is required.");
        }
        Item from = item(equipment, request.fromItemId(), "fromItemId");
        Item to = item(equipment, request.toItemId(), "toItemId");
        String fromId = from == null ? null : from.getItemId();
        String toId = to == null ? null : to.getItemId();
        boolean taken = changeoverRepository.findAllByEquipmentIdAndDeletedYn(equipment.getEquipmentId(), NOT_DELETED).stream()
            .anyMatch(rule -> Objects.equals(rule.getFromItemId(), fromId) && Objects.equals(rule.getToItemId(), toId));
        if (taken) {
            throw new BusinessException(ErrorCode.CONFLICT, "A changeover from " + label(from) + " to " + label(to)
                + " is already set on this equipment; change its time instead.");
        }
        EquipmentChangeover rule = new EquipmentChangeover();
        rule.setChangeoverId(idGenerator.generate());
        rule.setProjectId(equipment.getProjectId());
        rule.setEquipmentId(equipment.getEquipmentId());
        rule.setFromItemId(fromId);
        rule.setToItemId(toId);
        rule.setChangeoverMinutes(minutes(request.minutes()));
        rule.setNote(note(request.note()));
        rule.setCreatedBy(projectAccessService.requireCurrentUserId());
        changeoverRepository.save(rule);
        return responses(equipment);
    }

    @Transactional
    public List<EquipmentChangeoverResponse> update(String equipmentId, String changeoverId, EquipmentChangeoverUpdateRequest request) {
        Equipment equipment = findEquipmentForWrite(equipmentId);
        EquipmentChangeover rule = findRule(equipment, changeoverId);
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "minutes is required.");
        }
        rule.setChangeoverMinutes(minutes(request.minutes()));
        rule.setNote(note(request.note()));
        rule.setUpdatedBy(projectAccessService.requireCurrentUserId());
        changeoverRepository.save(rule);
        return responses(equipment);
    }

    @Transactional
    public List<EquipmentChangeoverResponse> remove(String equipmentId, String changeoverId) {
        Equipment equipment = findEquipmentForWrite(equipmentId);
        EquipmentChangeover rule = findRule(equipment, changeoverId);
        rule.setDeletedYn("Y");
        rule.setUpdatedBy(projectAccessService.requireCurrentUserId());
        changeoverRepository.save(rule);
        return responses(equipment);
    }

    /**
     * The changeover from making {@code fromItemId} to making {@code toItemId} on the equipment, with no access check
     * (callers check it). Empty when no rule applies, or when the item stays the same without an exact rule for it.
     */
    public Optional<Match> changeover(String equipmentId, String fromItemId, String toItemId) {
        if (fromItemId == null || toItemId == null) {
            return Optional.empty();
        }
        List<EquipmentChangeover> rules = changeoverRepository.findAllByEquipmentIdAndDeletedYn(equipmentId, NOT_DELETED);
        Optional<EquipmentChangeover> exact = rules.stream()
            .filter(rule -> fromItemId.equals(rule.getFromItemId()) && toItemId.equals(rule.getToItemId()))
            .findFirst();
        if (exact.isPresent() || fromItemId.equals(toItemId)) {
            return exact.map(rule -> new Match(rule.getChangeoverMinutes(), rule.getChangeoverId()));
        }
        Optional<EquipmentSetupChangeoverService.Match> attribute=setupChangeovers.match(equipmentId,fromItemId,toItemId);
        if(attribute.isPresent())return attribute.map(rule->new Match(rule.minutes(),rule.changeoverId()));
        List<Predicate<EquipmentChangeover>> fallbacks = List.of(
            rule -> fromItemId.equals(rule.getFromItemId()) && rule.getToItemId() == null,
            rule -> rule.getFromItemId() == null && toItemId.equals(rule.getToItemId()),
            rule -> rule.getFromItemId() == null && rule.getToItemId() == null);
        return fallbacks.stream()
            .map(test -> rules.stream().filter(test).findFirst())
            .flatMap(Optional::stream)
            .findFirst()
            .map(rule -> new Match(rule.getChangeoverMinutes(), rule.getChangeoverId()));
    }

    /** Exact pairs first, then rules with one side open, then the any-to-any rule; by item code within each. */
    private List<EquipmentChangeoverResponse> responses(Equipment equipment) {
        List<EquipmentChangeover> rules = changeoverRepository.findAllByEquipmentIdAndDeletedYn(equipment.getEquipmentId(), NOT_DELETED);
        Map<String, Item> items = itemRepository.findAllById(rules.stream()
                .flatMap(rule -> Stream.of(rule.getFromItemId(), rule.getToItemId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()))
            .stream().collect(Collectors.toMap(Item::getItemId, Function.identity()));
        Function<String, String> code = id -> id == null ? "" : items.containsKey(id) ? items.get(id).getItemCode() : id;
        return rules.stream()
            .sorted(Comparator.<EquipmentChangeover>comparingInt(rule -> (rule.getFromItemId() == null ? 1 : 0) + (rule.getToItemId() == null ? 1 : 0))
                .thenComparing(rule -> code.apply(rule.getFromItemId()))
                .thenComparing(rule -> code.apply(rule.getToItemId())))
            .map(rule -> {
                Item from = rule.getFromItemId() == null ? null : items.get(rule.getFromItemId());
                Item to = rule.getToItemId() == null ? null : items.get(rule.getToItemId());
                return new EquipmentChangeoverResponse(rule.getChangeoverId(), rule.getEquipmentId(), rule.getFromItemId(),
                    from == null ? null : from.getItemCode(), from == null ? null : from.getItemName(), rule.getToItemId(),
                    to == null ? null : to.getItemCode(), to == null ? null : to.getItemName(), rule.getChangeoverMinutes(), rule.getNote());
            })
            .toList();
    }

    private Item item(Equipment equipment, String itemId, String name) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        return itemRepository.findByItemIdAndDeletedYn(itemId.trim(), NOT_DELETED)
            .filter(found -> equipment.getProjectId().equals(found.getProjectId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, name + " is not an item of this project."));
    }

    private EquipmentChangeover findRule(Equipment equipment, String changeoverId) {
        return changeoverRepository.findByChangeoverIdAndDeletedYn(changeoverId, NOT_DELETED)
            .filter(found -> equipment.getEquipmentId().equals(found.getEquipmentId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    /** Serialize rule changes before reading a rule so deletion and the previous committed values cannot be undone. */
    private Equipment findEquipmentForWrite(String equipmentId) {
        Equipment equipment = findEquipment(equipmentId);
        projectAccessService.requireProjectWriteAccess(equipment.getProjectId());
        entities.refresh(equipment, LockModeType.PESSIMISTIC_WRITE);
        if (!NOT_DELETED.equals(equipment.getDeletedYn())) throw new BusinessException(ErrorCode.NOT_FOUND);
        return equipment;
    }

    private Equipment findEquipment(String equipmentId) {
        return equipmentRepository.findByEquipmentIdAndDeletedYn(equipmentId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private static int minutes(Integer minutes) {
        if (minutes == null || minutes < 1 || minutes > LONGEST_MINUTES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "minutes must be a whole number from 1 to " + LONGEST_MINUTES + ".");
        }
        return minutes;
    }

    private static String note(String note) {
        String trimmed = note == null || note.isBlank() ? null : note.trim();
        if (trimmed != null && trimmed.length() > 500) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "note can be at most 500 characters.");
        }
        return trimmed;
    }

    private static String label(Item item) {
        return item == null ? "any item" : item.getItemCode();
    }
}
