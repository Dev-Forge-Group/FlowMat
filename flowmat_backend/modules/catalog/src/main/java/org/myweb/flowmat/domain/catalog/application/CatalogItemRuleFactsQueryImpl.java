package org.myweb.flowmat.domain.catalog.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemRuleFactsQuery;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.catalog.repository.ItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CatalogItemRuleFactsQueryImpl implements CatalogItemRuleFactsQuery {

    private static final TypeReference<Map<String, Object>> PROPERTIES = new TypeReference<>() {
    };

    private final ItemRepository itemRepository;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<Map<String, Object>> findActiveItem(String itemId) {
        return itemRepository.findByItemIdAndDeletedYn(itemId, "N").map(this::facts);
    }

    // The steps rule facts took before: the item as JSON, read back as a detached Item, then the map the rule
    // evaluator makes of an object (FlowRuleExpressionEvaluator.resolvePath). Each value keeps its type and form.
    private Map<String, Object> facts(Item item) {
        try {
            Item copy = objectMapper.readValue(objectMapper.writeValueAsString(Hibernate.unproxy(item)), Item.class);
            return Collections.unmodifiableMap(objectMapper.convertValue(copy, PROPERTIES));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot read the item's rule facts.", error);
        }
    }
}
