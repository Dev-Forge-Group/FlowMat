package org.myweb.flowmat.domain.workflow.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessIo;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessIoRepository extends JpaRepository<ProcessIo, String> {

    List<ProcessIo> findAllByProcessIdAndDeletedYnOrderByCreatedAtAsc(String processId, String deletedYn);

    List<ProcessIo> findAllByProcessIdInAndDeletedYnOrderByCreatedAtAsc(Collection<String> processIds, String deletedYn);

    Optional<ProcessIo> findByProcessIoIdAndDeletedYn(String processIoId, String deletedYn);
}
