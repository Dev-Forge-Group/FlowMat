package org.myweb.flowmat.domain.workflow.application;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.myweb.flowmat.domain.workflow.domain.contract.PortSchema;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessConnection;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessIo;
import org.myweb.flowmat.domain.workflow.domain.expression.ConditionExpression;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

/**
 * What one connection must agree with: its condition reads attributes its source port declares, it joins two different
 * processes, its ports are not already joined, its ends are the processes' output and input ports, and the ports' items,
 * resource types, schemas and unit types match. A pure check over the values it is given, so saving a connection, the
 * workflow check and the flow kernel's fixtures (ADR-040) apply the same rule.
 */
final class ConnectionContract {

    private ConnectionContract() {
    }

    /**
     * @param workflowConnections the workflow's live connections, asked only when both ends name a port
     * @param ports               a live port by id; null when there is none
     * @param inferItem           whether a connection without an item takes the item both ports share
     * @throws BusinessException  what is wrong, as saving the connection reports it
     */
    static void check(ProcessConnection connection, String excludedConnectionId,
                      Supplier<List<ProcessConnection>> workflowConnections, Function<String, ProcessIo> ports,
                      WorkflowUnits units, boolean inferItem) {
        ConditionExpression condition = null;
        if (hasText(connection.getConditionExpr())) {
            condition = ConditionExpression.compile(connection.getConditionExpr());
        }
        if (connection.getFromProcessId().equals(connection.getToProcessId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "A connection cannot link a process to itself.");
        }
        if (connection.getFromIoId() != null && connection.getToIoId() != null
            && workflowConnections.get().stream().anyMatch(existing ->
                !existing.getConnectionId().equals(excludedConnectionId)
                    && connection.getFromIoId().equals(existing.getFromIoId())
                    && connection.getToIoId().equals(existing.getToIoId()))) {
            throw new BusinessException(ErrorCode.CONFLICT, "Ports are already connected.");
        }
        String fromIoId = connection.getFromIoId();
        String toIoId = connection.getToIoId();
        ProcessIo source = fromIoId == null ? null : ports.apply(fromIoId);
        if (fromIoId != null && source == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Connection fromIoId points to a deleted port.");
        }
        ProcessIo target = toIoId == null ? null : ports.apply(toIoId);
        if (toIoId != null && target == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Connection toIoId points to a deleted port.");
        }
        if (source != null && (!source.getProcessId().equals(connection.getFromProcessId())
            || !"output".equalsIgnoreCase(source.getDirection()))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Connection fromIoId must be an output port of fromProcessId.");
        }
        if (target != null && (!target.getProcessId().equals(connection.getToProcessId())
            || !"input".equalsIgnoreCase(target.getDirection()))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Connection toIoId must be an input port of toProcessId.");
        }
        PortSchema sourceSchema = source == null ? null : PortSchema.parseStored(source.getSchemaJson());
        if (condition != null) {
            condition.requireDeclaredAttributes(sourceSchema == null ? null : sourceSchema.properties().keySet());
        }
        String sourceItem = source == null ? null : source.getItemId();
        String targetItem = target == null ? null : target.getItemId();
        String selectedItem = connection.getItemId();
        if (sourceItem != null && targetItem != null && !sourceItem.equals(targetItem)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Connected ports itemId must match.");
        }
        if (selectedItem != null && ((sourceItem != null && !selectedItem.equals(sourceItem))
            || (targetItem != null && !selectedItem.equals(targetItem)))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Connection itemId must match the ports.");
        }
        if (inferItem && selectedItem == null && sourceItem != null && sourceItem.equals(targetItem)) {
            connection.setItemId(sourceItem);
        }
        if (source != null && target != null) {
            String sourceType = defaultIfBlank(source.getResourceType(), source.getIoType());
            String targetType = defaultIfBlank(target.getResourceType(), target.getIoType());
            // Both blank with no old ioType read as the same (none), not as a failure of the check itself.
            if (!Objects.equals(sourceType, targetType)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Connected ports resourceType must match.");
            }
            PortSchema targetSchema = PortSchema.parseStored(target.getSchemaJson());
            PortSchema.requireCompatible(sourceSchema, targetSchema);
        }
        Set<String> types = new HashSet<>();
        for (String unit : new String[] {source == null ? null : source.getUnit(),
            target == null ? null : target.getUnit(), connection.getUnit()}) {
            if (unit != null && !unit.isBlank()) {
                units.type(unit).ifPresent(types::add);
            }
        }
        if (types.size() > 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Connection unit type must match port units.");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** As saving reads a resource type: trimmed and lower case, the other value when blank. */
    private static String defaultIfBlank(String value, String defaultValue) {
        return hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : defaultValue;
    }
}
