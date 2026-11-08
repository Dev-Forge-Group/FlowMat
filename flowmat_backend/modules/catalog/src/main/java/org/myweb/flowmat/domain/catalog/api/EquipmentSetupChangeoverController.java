package org.myweb.flowmat.domain.catalog.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.api.dto.request.SetupChangeoverRequest;
import org.myweb.flowmat.domain.catalog.api.dto.response.EquipmentSetupChangeoverResponse;
import org.myweb.flowmat.domain.catalog.application.EquipmentSetupChangeoverService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/equipments/{equipmentId}/setup-changeovers")
public class EquipmentSetupChangeoverController {
    private final EquipmentSetupChangeoverService service;
    @GetMapping public ApiResponse<List<EquipmentSetupChangeoverResponse>> list(@PathVariable String equipmentId){return ApiResponse.ok(service.list(equipmentId));}
    @PutMapping("/{changeoverId}") public ApiResponse<EquipmentSetupChangeoverResponse> save(@PathVariable String equipmentId,@PathVariable String changeoverId,@RequestBody JsonNode body){
        String id=SetupInput.id(changeoverId);
        var request=new SetupChangeoverRequest(SetupInput.attributes(body,"fromAttributes"),SetupInput.attributes(body,"toAttributes"),
            SetupInput.integer(body,"priority",100000),SetupInput.integer(body,"minutes",10080),SetupInput.note(body),SetupInput.version(body));
        return ApiResponse.ok(service.save(equipmentId,id,request));
    }
    @DeleteMapping("/{changeoverId}") public ApiResponse<List<EquipmentSetupChangeoverResponse>> remove(@PathVariable String equipmentId,@PathVariable String changeoverId,@RequestParam(required=false) String expectedVersion){return ApiResponse.ok(service.remove(equipmentId,SetupInput.id(changeoverId),SetupInput.version(expectedVersion)));}
    @ExceptionHandler(HttpMessageNotReadableException.class) public ResponseEntity<ApiResponse<Void>> malformed(){return ResponseEntity.badRequest().body(ApiResponse.error("fromAttributes, toAttributes, priority, minutes and expectedVersion require valid JSON."));}
}
