package org.myweb.flowmat.domain.integration.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorHttpGateway;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorHttpOutcome;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorMappingQuery;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorProfileOperation;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorProfileQuery;
import org.myweb.flowmat.domain.integration.api.dto.response.IntegrationRunResponse.Item;
import org.myweb.flowmat.domain.integration.application.IntegrationRunService.Result;
import org.myweb.flowmat.domain.integration.domain.entity.IntegrationJob;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.springframework.stereotype.Component;

/**
 * A push of the project's stock to another system (docs/architecture/adr/ADR-038-integration-jobs.md decision 2), in
 * settingTool's order: read the target, send only the items whose quantity differs, read the target again and say for
 * each item sent whether it now matches. Quantities are sent as they are, not as changes, so a push sent twice, or one
 * whose answer was lost, does not add twice. Called with no transaction open.
 */
@Component
@RequiredArgsConstructor
class StockPush {

    /** Items one push sends at most, as for a pull's records. */
    static final int MAX_SENT = PullRows.MAX_ROWS;

    private final ConnectorProfileQuery profiles;
    private final ConnectorHttpGateway gateway;
    private final ConnectorMappingQuery mappings;
    private final CatalogQuery catalog;
    private final StockQuery stock;
    private final ObjectMapper json;

    /** With {@code send} false this only reads the target and says what would be sent: a preview. */
    Result run(IntegrationJob job, JsonNode mapping, boolean send) {
        String projectId = job.getProjectId();
        ConnectorProfileOperation verify = profiles.requireOperation(projectId, job.getProfileVersionId(), job.getVerifyOperationId());
        ConnectorProfileOperation update = profiles.requireOperation(projectId, job.getProfileVersionId(), job.getOperationId());
        Target before;
        try {
            before = read(verify, mapping);
        } catch (PullRows.PullProblem problem) {
            return Result.failed(problem.getMessage());
        }

        // FlowMat's side: every item with a stock record, and every item the target lists that FlowMat knows.
        Map<String, CatalogItemView> byCode = new LinkedHashMap<>();
        catalog.findProjectItems(projectId).stream().sorted(Comparator.comparing(CatalogItemView::itemCode))
            .forEach(item -> byCode.putIfAbsent(item.itemCode(), item));
        Map<String, BigDecimal> held = stock.quantityOnHandByItem(projectId);
        List<String> codes = new ArrayList<>();
        byCode.values().stream().filter(item -> held.containsKey(item.itemId())).forEach(item -> codes.add(item.itemCode()));
        before.quantities().keySet().stream().filter(code -> !codes.contains(code)).forEach(codes::add);

        List<Line> lines = new ArrayList<>();
        List<Item> planned = new ArrayList<>();
        int notInFlowMat = 0;
        for (String code : codes) {
            int number = planned.size() + 1;
            CatalogItemView item = byCode.get(code);
            String theirs = before.quantities().get(code);
            if (item == null) {
                notInFlowMat++;
                planned.add(new Item(number, code, "not_in_flowmat", "The target lists it; FlowMat has no such item."));
                continue;
            }
            BigDecimal ours = held.getOrDefault(item.itemId(), BigDecimal.ZERO).stripTrailingZeros();
            if (theirs == null ? ours.signum() == 0 : same(theirs, ours)) {
                planned.add(new Item(number, code, "same", null));
                continue;
            }
            lines.add(new Line(number, code, ours, item.unitCode()));
            planned.add(new Item(number, code, "send", theirs == null ? "The target does not list it; FlowMat has "
                + ours.toPlainString() + "." : "The target has " + theirs + "; FlowMat has " + ours.toPlainString() + "."));
        }
        int total = planned.size();
        String extra = notInFlowMat == 0 ? null : notInFlowMat + " of the items the target lists are not in FlowMat.";
        if (lines.size() > MAX_SENT) {
            return new Result("failed", total, 0, notInFlowMat, lines.size() + " items differ; a run sends at most " + MAX_SENT + ".", planned);
        }
        if (lines.isEmpty()) {
            return new Result("succeeded", total, 0, notInFlowMat, join("Every item already matches the target.", extra), planned);
        }
        if (!send) {
            return new Result("succeeded", total, lines.size(), notInFlowMat, join(lines.size() + " items would be sent.", extra), planned);
        }

        JsonNode body;
        try {
            body = body(lines, mapping.get("request"));
        } catch (BusinessException exception) {
            return new Result("failed", total, 0, notInFlowMat, "The request mapping does not fit FlowMat's document; nothing was sent.", planned);
        }
        ConnectorHttpOutcome sent = gateway.execute(update, body);
        boolean answered = "SUCCEEDED".equals(sent.status());
        if (!answered && !sent.mayHaveBeenSent()) {
            return new Result("failed", total, 0, notInFlowMat, "The update could not be sent (" + sent.errorCode() + ").",
                replace(planned, lines, line -> new Item(line.number(), line.code(), "not_sent", null)));
        }

        // Whatever the answer, what the target holds now is the result.
        Target after;
        try {
            after = read(verify, mapping);
        } catch (PullRows.PullProblem problem) {
            String what = answered ? "The update was sent"
                : "rejected".equals(kind(sent)) ? "The target refused the update with HTTP " + sent.httpStatus()
                : "The update may have been sent";
            return new Result("outcome_unknown", total, 0, notInFlowMat, what + ", but reading the target back failed: "
                + problem.getMessage(), replace(planned, lines, line -> new Item(line.number(), line.code(), "unknown", null)));
        }
        int matched = 0;
        List<Item> results = new ArrayList<>(planned);
        for (Line line : lines) {
            String now = after.quantities().get(line.code());
            Item result;
            if (now == null) {
                result = new Item(line.number(), line.code(), "not_listed", "The target does not list it after the update.");
            } else if (same(now, line.quantity())) {
                matched++;
                result = new Item(line.number(), line.code(), "matched", null);
            } else {
                result = new Item(line.number(), line.code(), "different", "FlowMat sent " + line.quantity().toPlainString()
                    + "; the target has " + now + ".");
            }
            results.set(line.number() - 1, result);
        }
        int unmatched = lines.size() - matched;
        int leftOut = notInFlowMat + unmatched;
        if ("rejected".equals(kind(sent))) {
            return new Result("failed", total, matched, leftOut, "The target refused the update with HTTP " + sent.httpStatus() + ".", results);
        }
        if (!answered) {
            return unmatched == 0
                ? new Result("succeeded", total, matched, leftOut, join("The update's answer was lost; reading back shows every item sent matches.", extra), results)
                : new Result("outcome_unknown", total, matched, leftOut, "The update's answer was lost; " + unmatched + " of "
                    + lines.size() + " items sent do not match when read back.", results);
        }
        return unmatched == 0
            ? new Result("succeeded", total, matched, leftOut, extra, results)
            : new Result("partial", total, matched, leftOut, unmatched + " of " + lines.size() + " items sent do not match after the update.", results);
    }

    /** The target's quantity by item code, read with the job's row mapping. Records it cannot read are passed over. */
    private Target read(ConnectorProfileOperation verify, JsonNode mapping) {
        ConnectorHttpOutcome answer = gateway.execute(verify, null);
        if (!"SUCCEEDED".equals(answer.status())) {
            throw new PullRows.PullProblem(answer.httpStatus() != null
                ? "The target answered HTTP " + answer.httpStatus() + " when read."
                : "The target could not be read (" + answer.errorCode() + ").");
        }
        PullRows.Result records = PullRows.read(answer.response(), mapping, mappings, "itemCode");
        Map<String, String> quantities = new LinkedHashMap<>();
        for (int index = 0; index < records.rows().size(); index++) {
            Map<String, String> row = records.rows().get(index);
            String code = row.get("itemCode");
            if (records.problems().containsKey(index + 1) || code == null || code.isBlank()) continue;
            quantities.putIfAbsent(code.trim(), row.get("quantity"));
        }
        return new Target(quantities);
    }

    /** FlowMat's document, {"items": [{"itemCode", "quantity", "unit"}]}, through the job's request mapping if it has one. */
    private JsonNode body(List<Line> lines, JsonNode request) {
        ObjectNode document = json.createObjectNode();
        ArrayNode items = document.putArray("items");
        for (Line line : lines) {
            ObjectNode item = items.addObject();
            item.put("itemCode", line.code());
            item.put("quantity", line.quantity());
            item.put("unit", line.unit());
        }
        return request == null || request.isNull() ? document : mappings.map(document, request);
    }

    private static boolean same(String theirs, BigDecimal ours) {
        try {
            return theirs != null && new BigDecimal(theirs.trim()).compareTo(ours) == 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static String kind(ConnectorHttpOutcome outcome) {
        return "HTTP_REJECTED".equals(outcome.status()) ? "rejected" : outcome.status();
    }

    private static List<Item> replace(List<Item> planned, List<Line> lines, java.util.function.Function<Line, Item> result) {
        List<Item> items = new ArrayList<>(planned);
        for (Line line : lines) items.set(line.number() - 1, result.apply(line));
        return items;
    }

    private static String join(String first, String second) {
        return second == null ? first : first + " " + second;
    }

    private record Target(Map<String, String> quantities) {
    }

    private record Line(int number, String code, BigDecimal quantity, String unit) {
    }
}
