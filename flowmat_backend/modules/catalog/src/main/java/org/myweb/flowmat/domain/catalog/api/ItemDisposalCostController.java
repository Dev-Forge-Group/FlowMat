package org.myweb.flowmat.domain.catalog.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.api.dto.request.ItemDisposalCostRequest;
import org.myweb.flowmat.domain.catalog.api.dto.response.ItemDisposalCostChangeResponse;
import org.myweb.flowmat.domain.catalog.api.dto.response.ItemDisposalCostResponse;
import org.myweb.flowmat.domain.catalog.application.ItemDisposalCostService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** An item's disposal cost (docs/domain/bom-by-products.md WD2). Read: Project read; save: Project write. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/items/{itemId}/disposal-cost")
public class ItemDisposalCostController {
    private final ItemDisposalCostService service;

    @GetMapping
    public ApiResponse<ItemDisposalCostResponse> get(@PathVariable("itemId") String itemId) {
        return ApiResponse.ok(service.get(itemId));
    }

    /** Its changes, newest first (docs/domain/bom-by-products.md WD7). Project read. */
    @GetMapping("/history")
    public ApiResponse<List<ItemDisposalCostChangeResponse>> history(@PathVariable("itemId") String itemId) {
        return ApiResponse.ok(service.history(itemId));
    }

    @PutMapping
    public ApiResponse<ItemDisposalCostResponse> set(@PathVariable("itemId") String itemId, @RequestBody DisposalCostBody request) {
        JsonNode body = request == null ? null : request.value();
        if (body == null || !body.isObject() || !body.has("disposalCost")
            || !(body.get("disposalCost").isNull() || body.get("disposalCost").isNumber()))
            throw new BusinessException(ErrorCode.BAD_REQUEST, "disposalCost is required as a number or explicit null.");
        JsonNode version = body.get("expectedVersion");
        if (version == null || !version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() < 0)
            throw new BusinessException(ErrorCode.BAD_REQUEST, "expectedVersion is required as a nonnegative integer.");
        return ApiResponse.ok(service.set(itemId, new ItemDisposalCostRequest(
            body.get("disposalCost").isNull() ? null : body.get("disposalCost").decimalValue(), version.longValue())));
    }

    /** Preserve JSON decimal tokens until the cost's precision rules have checked them. */
    @Schema(implementation = JsonNode.class)
    @JsonDeserialize(using = DisposalCostBodyReader.class)
    public record DisposalCostBody(JsonNode value) {
    }

    public static final class DisposalCostBodyReader extends StdDeserializer<DisposalCostBody> {
        public DisposalCostBodyReader() {
            super(DisposalCostBody.class);
        }

        @Override
        public DisposalCostBody deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.isExpectedStartObjectToken()) return new DisposalCostBody(parser.readValueAsTree());
            ObjectNode body = ((ObjectMapper) parser.getCodec()).createObjectNode();
            String rawCost = null;
            JsonToken token;
            while ((token = parser.nextToken()) == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                if (body.has(field) && context.isEnabled(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY))
                    throw JsonMappingException.from(parser, "Duplicate field in disposal cost request.");
                JsonToken value = parser.nextToken();
                if ("disposalCost".equals(field)) {
                    rawCost = value != null && value.isNumeric() ? parser.getText() : null;
                    body.set(field, rawCost == null ? parser.readValueAsTree() : DecimalNode.ZERO);
                } else {
                    body.set(field, parser.readValueAsTree());
                }
            }
            if (token != JsonToken.END_OBJECT) throw JsonMappingException.from(parser, "Expected a JSON object.");
            if (rawCost != null) {
                try {
                    body.set("disposalCost", DecimalNode.valueOf(zeroMantissa(rawCost) ? BigDecimal.ZERO : new BigDecimal(rawCost)));
                } catch (NumberFormatException invalidNumber) {
                    throw JsonMappingException.from(parser, "disposalCost exceeds the supported numeric range.", invalidNumber);
                }
            }
            return new DisposalCostBody(body);
        }

        private static boolean zeroMantissa(String number) {
            boolean digit = false;
            for (int i = 0; i < number.length(); i++) {
                char character = number.charAt(i);
                if (character == 'e' || character == 'E') break;
                if (character == '0') digit = true;
                else if (character != '.' && character != '-' && character != '+') return false;
            }
            return digit;
        }

        @Override
        public DisposalCostBody getNullValue(DeserializationContext context) {
            return new DisposalCostBody(NullNode.getInstance());
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> malformed() {
        return ResponseEntity.badRequest().body(ApiResponse.error(
            "disposalCost and expectedVersion require valid JSON numbers (disposalCost may be null)."));
    }
}
