package org.myweb.flowmat.domain.workflow.application;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowProcessOrder;
import org.myweb.flowmat.domain.workflow.domain.entity.Process;
import org.myweb.flowmat.domain.workflow.repository.ProcessConnectionRepository;
import org.myweb.flowmat.domain.workflow.repository.ProcessRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link WorkflowProcessOrder} from the live processes and connections of the current definition (docs/domain/worker-calendar.md LB34). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkflowProcessOrderService implements WorkflowProcessOrder {
    /** Gateway node types, as ProcessExecutionBindingService has them: a run passes them itself. */
    private static final Set<String> GATEWAYS = Set.of("exclusive", "join", "split");

    private final ProcessRepository processRepository;
    private final ProcessConnectionRepository processConnectionRepository;

    @Override
    public Map<String, Set<String>> processPredecessors(String workflowId) {
        Map<String, String> types = new LinkedHashMap<>();
        for (Process process : processRepository.findAllByWorkflowIdAndDeletedYnOrderByCreatedAtAsc(workflowId, "N")) {
            types.put(process.getProcessId(), process.getNodeType() == null ? "" : process.getNodeType().toLowerCase(Locale.ROOT));
        }
        Map<String, List<String>> into = new HashMap<>();
        processConnectionRepository.findAllByWorkflowIdAndDeletedYnOrderByCreatedAtAsc(workflowId, "N")
            .forEach(connection -> into.computeIfAbsent(connection.getToProcessId(), id -> new ArrayList<>()).add(connection.getFromProcessId()));
        Map<String, Set<String>> before = new LinkedHashMap<>();
        types.forEach((processId, type) -> {
            if (GATEWAYS.contains(type)) {
                return;
            }
            // Back along the connections, through gateways, to the first processes.
            Set<String> found = new LinkedHashSet<>();
            Set<String> seen = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>(into.getOrDefault(processId, List.of()));
            while (!queue.isEmpty()) {
                String earlier = queue.poll();
                if (!seen.add(earlier) || !types.containsKey(earlier)) {
                    continue;
                }
                if (GATEWAYS.contains(types.get(earlier))) {
                    queue.addAll(into.getOrDefault(earlier, List.of()));
                } else if (!earlier.equals(processId)) {
                    found.add(earlier);
                }
            }
            before.put(processId, Collections.unmodifiableSet(found));
        });
        return Collections.unmodifiableMap(before);
    }
}
