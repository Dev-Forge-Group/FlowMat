package org.myweb.flowmat.domain.inventory.repository;

import java.util.List;
import org.myweb.flowmat.domain.inventory.domain.entity.LotStatusEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LotStatusEventRepository extends JpaRepository<LotStatusEvent, String> {

    List<LotStatusEvent> findAllByLotIdOrderByCreatedAtDescLotStatusEventIdDesc(String lotId);
}
