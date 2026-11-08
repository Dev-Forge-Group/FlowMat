package org.myweb.flowmat.domain.catalog.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.api.dto.request.SetupChangeoverRequest;
import org.myweb.flowmat.domain.catalog.api.dto.response.EquipmentSetupChangeoverResponse;
import org.myweb.flowmat.domain.catalog.domain.entity.Equipment;
import org.myweb.flowmat.domain.catalog.domain.entity.EquipmentSetupChangeover;
import org.myweb.flowmat.domain.catalog.repository.EquipmentRepository;
import org.myweb.flowmat.domain.catalog.repository.EquipmentSetupChangeoverRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly=true)
public class EquipmentSetupChangeoverService {
    private final EquipmentRepository equipment;
    private final EquipmentSetupChangeoverRepository rules;
    private final ItemSetupAttributesService attributes;
    private final ProjectAccessService access;
    private final EntityManager entities;
    public record Match(int minutes,String changeoverId) { }

    public List<EquipmentSetupChangeoverResponse> list(String equipmentId) {
        Equipment found=live(equipmentId);access.requireProjectReadAccess(found.getProjectId());return responses(equipmentId);
    }
    @Transactional
    public EquipmentSetupChangeoverResponse save(String equipmentId,String id,SetupChangeoverRequest request) {
        Equipment found=lock(equipmentId);
        if(request.fromAttributes().isEmpty() && request.toAttributes().isEmpty())
            throw new BusinessException(ErrorCode.BAD_REQUEST,"fromAttributes or toAttributes must contain a predicate; use an existing default rule for any-to-any.");
        EquipmentSetupChangeover current=rules.findById(id).orElse(null);
        if(current!=null && !equipmentId.equals(current.getEquipmentId()))throw new BusinessException(ErrorCode.NOT_FOUND);
        if(current!=null && !"N".equals(current.getDeletedYn()))throw new BusinessException(ErrorCode.CONFLICT,"changeoverId was deleted; reload the rule list.");
        String actor=access.requireCurrentUserId();long version=current==null?0:current.getVersion();
        if(version!=request.expectedVersion()) {
            if(current!=null && version>0 && request.expectedVersion()==version-1 && actor.equals(current.getUpdatedBy()) && same(current,request))return response(current);
            throw new BusinessException(ErrorCode.CONFLICT,"expectedVersion changed; reload the setup changeover before saving.");
        }
        if(version==Long.MAX_VALUE)throw new BusinessException(ErrorCode.CONFLICT,"expectedVersion has reached its limit.");
        boolean occupied=active(equipmentId).stream().anyMatch(rule->rule.getPriority()==request.priority() && !id.equals(rule.getChangeoverId()));
        if(occupied)throw new BusinessException(ErrorCode.CONFLICT,"priority is already used on this equipment; choose another priority.");
        if(current==null){current=new EquipmentSetupChangeover();current.setChangeoverId(id);current.setEquipmentId(equipmentId);current.setCreatedBy(actor);}
        current.setFromAttributes(new TreeMap<>(request.fromAttributes()));current.setToAttributes(new TreeMap<>(request.toAttributes()));
        current.setPriority(request.priority());current.setMinutes(request.minutes());current.setNote(request.note());
        current.setVersion(version+1);current.setUpdatedBy(actor);
        return response(rules.save(current));
    }
    @Transactional
    public List<EquipmentSetupChangeoverResponse> remove(String equipmentId,String id,long expectedVersion) {
        lock(equipmentId);
        EquipmentSetupChangeover rule=rules.findById(id).filter(one->equipmentId.equals(one.getEquipmentId()) && "N".equals(one.getDeletedYn()))
            .orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND));
        if(rule.getVersion()!=expectedVersion)throw new BusinessException(ErrorCode.CONFLICT,"expectedVersion changed; reload the setup changeover before deleting.");
        rule.setDeletedYn("Y");rule.setUpdatedBy(access.requireCurrentUserId());rules.saveAndFlush(rule);
        return responses(equipmentId);
    }
    /** Lower configured priority wins within the attribute tier; each active priority is unique on the equipment. */
    Optional<Match> match(String equipmentId,String fromItem,String toItem) {
        Optional<Equipment> found=equipment.findByEquipmentIdAndDeletedYn(equipmentId,"N");
        if(found.isEmpty() || fromItem==null || toItem==null)return Optional.empty();
        Map<String,Map<String,String>> values=attributes.forItems(found.get().getProjectId(),List.of(fromItem,toItem));
        if(!values.containsKey(fromItem) || !values.containsKey(toItem))return Optional.empty();
        return active(equipmentId).stream().filter(rule->matches(rule.getFromAttributes(),values.get(fromItem)) && matches(rule.getToAttributes(),values.get(toItem)))
            .findFirst().map(rule->new Match(rule.getMinutes(),rule.getChangeoverId()));
    }
    private static boolean matches(Map<String,String> predicate,Map<String,String> values){return predicate.entrySet().stream().allMatch(entry->entry.getValue().equals(values.get(entry.getKey())));}
    private Equipment live(String id){return equipment.findByEquipmentIdAndDeletedYn(id,"N").orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND));}
    private Equipment lock(String id){Equipment found=live(id);access.requireProjectWriteAccess(found.getProjectId());entities.refresh(found,LockModeType.PESSIMISTIC_WRITE);if(!"N".equals(found.getDeletedYn()))throw new BusinessException(ErrorCode.NOT_FOUND);return found;}
    private List<EquipmentSetupChangeover> active(String id){return rules.findAllByEquipmentIdAndDeletedYnOrderByPriorityAsc(id,"N");}
    private List<EquipmentSetupChangeoverResponse> responses(String id){return active(id).stream().map(EquipmentSetupChangeoverService::response).toList();}
    private static boolean same(EquipmentSetupChangeover rule,SetupChangeoverRequest request){return rule.getFromAttributes().equals(request.fromAttributes()) && rule.getToAttributes().equals(request.toAttributes()) && rule.getPriority()==request.priority() && rule.getMinutes()==request.minutes() && Objects.equals(rule.getNote(),request.note());}
    private static EquipmentSetupChangeoverResponse response(EquipmentSetupChangeover rule){return new EquipmentSetupChangeoverResponse(rule.getChangeoverId(),rule.getEquipmentId(),new TreeMap<>(rule.getFromAttributes()),new TreeMap<>(rule.getToAttributes()),rule.getPriority(),rule.getMinutes(),rule.getNote(),rule.getVersion());}
}
