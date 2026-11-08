package org.myweb.flowmat.domain.workflow.repository;

import java.util.List;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessTemplateRepository extends JpaRepository<ProcessTemplate, String> {

    List<ProcessTemplate> findAllByOrderBySortOrderAscCreatedAtAsc();

    List<ProcessTemplate> findAllByPublicYnOrderBySortOrderAscCreatedAtAsc(String publicYn);
}
