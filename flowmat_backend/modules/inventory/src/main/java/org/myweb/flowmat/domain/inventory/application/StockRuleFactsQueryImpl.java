package org.myweb.flowmat.domain.inventory.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockRuleFactsQuery;
import org.myweb.flowmat.domain.inventory.domain.entity.Inventory;
import org.myweb.flowmat.domain.inventory.repository.InventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockRuleFactsQueryImpl implements StockRuleFactsQuery {

    private static final TypeReference<Map<String, Object>> PROPERTIES = new TypeReference<>() {
    };

    private final InventoryRepository inventoryRepository;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<Map<String, Object>> findActiveStock(String inventoryId) {
        return inventoryRepository.findByInventoryIdAndDeletedYn(inventoryId, "N").map(this::facts);
    }

    // The steps rule facts took before: the record as JSON, read back as a detached Inventory, then the map the rule
    // evaluator makes of an object (FlowRuleExpressionEvaluator.resolvePath). Each value keeps its type and form.
    private Map<String, Object> facts(Inventory row) {
        try {
            Inventory copy = objectMapper.readValue(objectMapper.writeValueAsString(Hibernate.unproxy(row)), Inventory.class);
            return Collections.unmodifiableMap(objectMapper.convertValue(copy, PROPERTIES));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot read the stock record's rule facts.", error);
        }
    }
}
