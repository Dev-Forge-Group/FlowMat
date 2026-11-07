package org.myweb.flowmat.domain.catalog.application;

import java.util.List;
import org.myweb.flowmat.domain.catalog.api.dto.request.ItemCreateRequest;
import org.myweb.flowmat.domain.catalog.api.dto.request.ItemUpdateRequest;
import org.myweb.flowmat.domain.catalog.api.dto.response.ItemResponse;

public interface ItemService {

    List<ItemResponse> listItems(String projectId);

    ItemResponse createItem(ItemCreateRequest request);

    ItemResponse getItem(String itemId);

    ItemResponse updateItem(String itemId, ItemUpdateRequest request);

    void deleteItem(String itemId);
}
