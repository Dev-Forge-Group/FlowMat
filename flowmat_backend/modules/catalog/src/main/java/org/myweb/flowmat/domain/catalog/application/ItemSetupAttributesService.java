package org.myweb.flowmat.domain.catalog.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.api.dto.response.ItemSetupAttributesResponse;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.catalog.domain.entity.ItemSetupAttributes;
import org.myweb.flowmat.domain.catalog.repository.ItemRepository;
import org.myweb.flowmat.domain.catalog.repository.ItemSetupAttributesRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly=true)
public class ItemSetupAttributesService {
    private final ItemRepository items;
    private final ItemSetupAttributesRepository attributes;
    private final ProjectAccessService access;
    private final EntityManager entities;

    public ItemSetupAttributesResponse get(String itemId) {
        Item item=live(itemId);access.requireProjectReadAccess(item.getProjectId());
        return attributes.findById(itemId).map(ItemSetupAttributesService::response)
            .orElse(new ItemSetupAttributesResponse(itemId,Collections.emptyMap(),0,null,null));
    }
    @Transactional
    public ItemSetupAttributesResponse save(String itemId,Map<String,String> values,long expectedVersion) {
        Item item=live(itemId);access.requireProjectWriteAccess(item.getProjectId());
        entities.refresh(item,LockModeType.PESSIMISTIC_WRITE);
        if(!"N".equals(item.getDeletedYn()))throw new BusinessException(ErrorCode.NOT_FOUND);
        String actor=access.requireCurrentUserId();
        ItemSetupAttributes current=attributes.findById(itemId).orElse(null);
        long version=current==null?0:current.getVersion();
        if(version!=expectedVersion) {
            if(current!=null && version>0 && expectedVersion==version-1 && actor.equals(current.getUpdatedBy())
                && values.equals(current.getAttributes()))return response(current);
            throw new BusinessException(ErrorCode.CONFLICT,"expectedVersion changed; reload the item's setup attributes before saving.");
        }
        if(version==Long.MAX_VALUE)throw new BusinessException(ErrorCode.CONFLICT,"expectedVersion has reached its limit.");
        if(current==null){current=new ItemSetupAttributes();current.setItemId(itemId);}
        current.setAttributes(new TreeMap<>(values));current.setVersion(version+1);current.setUpdatedBy(actor);
        current.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return response(attributes.save(current));
    }
    /** Matching uses only live items from the equipment's project, including items whose attributes are empty. */
    Map<String,Map<String,String>> forItems(String projectId,Collection<String> ids) {
        if(ids==null || ids.isEmpty())return Collections.emptyMap();
        Map<String,Map<String,String>> result=new HashMap<>();
        items.findAllById(ids).stream().filter(item->projectId.equals(item.getProjectId()) && "N".equals(item.getDeletedYn()))
            .forEach(item->result.put(item.getItemId(),Collections.emptyMap()));
        attributes.findAllById(result.keySet()).forEach(row->result.put(row.getItemId(),new TreeMap<>(row.getAttributes())));
        return result;
    }
    private Item live(String id){return items.findByItemIdAndDeletedYn(id,"N").orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND));}
    private static ItemSetupAttributesResponse response(ItemSetupAttributes row){return new ItemSetupAttributesResponse(row.getItemId(),new TreeMap<>(row.getAttributes()),row.getVersion(),row.getUpdatedBy(),row.getUpdatedAt());}
}
