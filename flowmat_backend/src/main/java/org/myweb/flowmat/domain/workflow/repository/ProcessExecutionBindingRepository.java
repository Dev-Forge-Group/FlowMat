package org.myweb.flowmat.domain.workflow.repository;

import java.util.Collection;
import java.util.List;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessExecutionBinding;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessExecutionBindingRepository extends JpaRepository<ProcessExecutionBinding, String> {
    List<ProcessExecutionBinding> findAllByProcessIdIn(Collection<String> processIds);
}
