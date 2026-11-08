package org.myweb.flowmat.domain.inventory.application;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.application.publicapi.LotQuery;
import org.myweb.flowmat.domain.inventory.application.publicapi.LotView;
import org.myweb.flowmat.domain.inventory.domain.entity.LotMaster;
import org.myweb.flowmat.domain.inventory.repository.LotMasterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LotQueryImpl implements LotQuery {

    private final LotMasterRepository lotMasterRepository;

    @Override
    public Optional<LotView> findProjectLot(String projectId, String lotId) {
        return lotMasterRepository.findById(lotId)
            .filter(lot -> Objects.equals(projectId, lot.getProjectId()))
            .map(LotQueryImpl::view);
    }

    @Override
    public Map<String, LotView> findLots(Collection<String> lotIds) {
        if (lotIds.isEmpty()) {
            // Unlike Map.of(), an empty map answers a lookup of a null id with null, as the collected map does.
            return Collections.emptyMap();
        }
        return lotMasterRepository.findAllById(lotIds).stream()
            .collect(Collectors.toMap(LotMaster::getLotId, LotQueryImpl::view));
    }

    private static LotView view(LotMaster lot) {
        return new LotView(lot.getLotId(), lot.getProjectId(), lot.getItemId(), lot.getLotNo(), lot.getLotStatus(), lot.getProductionRunId(),
            lot.getExpiryDate(), lot.getReceivedAt());
    }
}
