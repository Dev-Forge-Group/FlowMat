package org.myweb.flowmat.domain.catalog.api;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.api.dto.response.ItemSetupAttributesResponse;
import org.myweb.flowmat.domain.catalog.application.ItemSetupAttributesService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/items/{itemId}/setup-attributes")
public class ItemSetupAttributesController {
    private final ItemSetupAttributesService service;
    @GetMapping public ApiResponse<ItemSetupAttributesResponse> get(@PathVariable String itemId){return ApiResponse.ok(service.get(itemId));}
    @PutMapping public ApiResponse<ItemSetupAttributesResponse> save(@PathVariable String itemId,@RequestBody JsonNode body){return ApiResponse.ok(service.save(itemId,SetupInput.attributes(body,"attributes"),SetupInput.version(body)));}
    @ExceptionHandler(HttpMessageNotReadableException.class) public ResponseEntity<ApiResponse<Void>> malformed(){return ResponseEntity.badRequest().body(ApiResponse.error("attributes and expectedVersion require valid JSON."));}
}
