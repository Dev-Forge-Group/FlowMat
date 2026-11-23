package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** BOM fixtures are created only in this Testcontainers database, never the dev/session DB. */
@AutoConfigureMockMvc
class WarehouseAllocatedPickIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtProvider jwt;
    @Autowired JdbcTemplate jdbc;
    @Test
    void ownReservedStockIsPickedBeforeOlderFreeStockAndPartialCompletionKeepsItsReservation() throws Exception {
        var f=fixture(6);var plan=data(pick(f));assertEquals(1,plan.path("tasks").size());
        var task=plan.path("tasks").get(0);assertEquals(f.stock(),task.path("inventoryId").asText());
        assertEquals(f.allocation(),task.path("allocationId").asText());assertEquals(6,task.path("quantity").asInt());
        String id=task.path("taskId").asText();var part=data(call(post("/warehouse-tasks/"+id+"/complete"),Map.of("quantity",2)));
        assertEquals("done",part.path("status").asText());assertStock(f.stock(),4,4);
        assertEquals(4,jdbc.queryForObject("select quantity from warehouse_task where task_id=?",Integer.class,id));
        assertEquals(0,data(pick(f)).path("tasks").size());
        data(call(post("/warehouse-tasks/"+id+"/complete"),null));assertStock(f.stock(),0,0);
        String destination=jdbc.queryForObject("select inventory_id from inventory where item_id=? and location='ALLOC-STAGING'",String.class,f.item());
        assertStock(destination,6,6);assertStock(f.freeStock(),10,0);
    }
    @Test
    void partialAllocationPlansSeparateReservedAndFreeTasksWithoutRepeatingThem() throws Exception {
        var f=fixture(4);var plan=data(pick(f));assertEquals(2,plan.path("tasks").size());
        assertEquals(4,plan.path("tasks").get(0).path("quantity").asInt());
        assertEquals(f.allocation(),plan.path("tasks").get(0).path("allocationId").asText());
        assertEquals(true,plan.path("tasks").get(1).path("allocationId").isNull());
        assertEquals(2,plan.path("tasks").get(1).path("quantity").asInt());
        assertEquals(0,data(pick(f)).path("tasks").size());
        for(var task:plan.path("tasks"))data(call(post("/warehouse-tasks/"+task.path("taskId").asText()+"/complete"),null));
        String destination=jdbc.queryForObject("select inventory_id from inventory where item_id=? and location='ALLOC-STAGING'",String.class,f.item());
        assertStock(destination,6,4);
    }
    @Test
    void aReleasedAllocationCannotSilentlyBecomeAFreeStockTask() throws Exception {
        var f=fixture(6);var task=data(pick(f)).path("tasks").get(0);
        data(call(post("/work-orders/"+f.order()+"/allocations/"+f.allocation()+"/release"),null));
        call(post("/warehouse-tasks/"+task.path("taskId").asText()+"/complete"),null).andExpect(status().isConflict());
        assertStock(f.stock(),6,0);assertEquals("open",jdbc.queryForObject("select status from warehouse_task where task_id=?",String.class,task.path("taskId").asText()));
    }
    @Test
    void anotherOrdersReservedStockDoesNotCoverThisOrdersShortageOrMoveWithItsPicks() throws Exception {
        var f=fixture(4);
        String other=data(call(post("/work-orders"),Map.of("projectId",DEMO_PROJECT,"workOrderTitle","Other reserved pick","targetQuantity",1))).path("workOrderId").asText();
        data(call(post("/work-orders/"+other+"/approve"),null));String otherAllocation=UUID.randomUUID().toString();
        jdbc.update("insert into stock_allocation(allocation_id,project_id,work_order_id,inventory_id,item_id,quantity,consumed_quantity,released_quantity,status,created_by,created_at) values(?,?,?,?,?,2,0,0,'open',?,now())",otherAllocation,DEMO_PROJECT,other,f.stock(),f.item(),DEMO_OWNER);
        jdbc.update("update inventory set reserved_quantity=6,available_quantity=0,version=version+1 where inventory_id=?",f.stock());
        var planned=data(pick(f));assertEquals(2,planned.path("tasks").size());
        assertEquals(4,planned.path("tasks").get(0).path("quantity").asInt());
        assertEquals(f.stock(),planned.path("tasks").get(0).path("inventoryId").asText());
        assertEquals(f.freeStock(),planned.path("tasks").get(1).path("inventoryId").asText());
        for(var task:planned.path("tasks"))data(call(post("/warehouse-tasks/"+task.path("taskId").asText()+"/complete"),null));
        assertStock(f.stock(),2,2);assertEquals(2,jdbc.queryForObject("select quantity from stock_allocation where allocation_id=?",Integer.class,otherAllocation));
    }
    @Test
    void anExpiredAllocatedLotIsNotAPickingCandidate() throws Exception {
        var f=fixture(6);
        jdbc.update("update item set lot_manage_yn='Y' where item_id=?",f.item());
        String lot=data(call(post("/lots"),Map.of("projectId",DEMO_PROJECT,"itemId",f.item(),"lotNo","EXPIRED-"+UUID.randomUUID().toString().substring(0,8),
            "expiryDate",java.time.LocalDate.now().minusDays(1).toString()))).path("lotId").asText();
        jdbc.update("update inventory set lot_id=? where inventory_id=?",lot,f.stock());jdbc.update("update stock_allocation set lot_id=? where allocation_id=?",lot,f.allocation());
        // No alternative stock, so an expired reservation cannot count as a usable candidate.
        jdbc.update("update inventory set inventory_status='quarantined' where inventory_id=?",f.freeStock());
        var planned=data(pick(f));assertEquals(0,planned.path("tasks").size());assertEquals(6,planned.path("lines").get(0).path("shortage").asInt());
        assertStock(f.stock(),6,6);
    }
    @Test
    void partialPickRoundingKeepsTaskStockAndAllocationsBalanced() throws Exception {
        var f=fixture(6);var task=data(pick(f)).path("tasks").get(0);String id=task.path("taskId").asText();
        call(post("/warehouse-tasks/"+id+"/complete"),Map.of("quantity",new java.math.BigDecimal("0.00001"))).andExpect(status().isBadRequest());
        var part=data(call(post("/warehouse-tasks/"+id+"/complete"),Map.of("quantity",new java.math.BigDecimal("1.23455"))));
        assertEquals(0,part.path("quantity").decimalValue().compareTo(new java.math.BigDecimal("1.2346")));
        for(String table:List.of("inventory","warehouse_task","stock_allocation")) {
            String key=table.equals("inventory")?"inventory_id":table.equals("warehouse_task")?"task_id":"allocation_id";
            String value=table.equals("inventory")?f.stock():table.equals("warehouse_task")?id:f.allocation();
            assertEquals(0,jdbc.queryForObject("select quantity from "+table+" where "+key+"=?",java.math.BigDecimal.class,value).compareTo(new java.math.BigDecimal("4.7654")));
        }
        assertEquals(0,jdbc.queryForObject("select reserved_quantity from inventory where inventory_id=?",java.math.BigDecimal.class,f.stock()).compareTo(new java.math.BigDecimal("4.7654")));
    }
    private record Fixture(String item,String stock,String freeStock,String order,String allocation){}
    private Fixture fixture(int allocated)throws Exception {
        String item=item("unit_kg");String product=item("unit_ea");
        String stock=stock(item,6,"ALLOC-SOURCE");
        String bom=data(call(post("/boms"),Map.of("projectId",DEMO_PROJECT,"targetItemId",product,"bomName","Allocated pick","baseQuantity",1,"baseUnit","ea"))).path("bomId").asText();
        data(call(post("/boms/"+bom+"/lines"),Map.of("childItemId",item,"quantity",6,"unit","kg")));
        data(call(post("/boms/"+bom+"/submit"),null));data(call(post("/boms/"+bom+"/approve"),null));
        String order=data(call(post("/work-orders"),Map.of("projectId",DEMO_PROJECT,"workOrderTitle","Reserved pick","targetQuantity",1,"bomId",bom))).path("workOrderId").asText();
        data(call(post("/work-orders/"+order+"/approve"),null));
        String allocation=data(call(post("/work-orders/"+order+"/allocations"),Map.of("lines",List.of(Map.of("itemId",item,"quantity",allocated))))).path("allocations").get(0).path("allocationId").asText();
        String free=stock(item,10,"FREE-SOURCE");jdbc.update("update inventory set created_at='2000-01-01T00:00:00Z' where inventory_id=?",free);
        return new Fixture(item,stock,free,order,allocation);
    }
    private ResultActions pick(Fixture f)throws Exception{return call(post("/warehouse-tasks/pick-list"),Map.of("projectId",DEMO_PROJECT,"workOrderId",f.order(),"stagingLocation","ALLOC-STAGING"));}
    private String item(String unit)throws Exception{return data(call(post("/items"),Map.of("projectId",DEMO_PROJECT,"itemCode","WP-"+UUID.randomUUID(),"itemName","Reserved pick","unitId",unit))).path("itemId").asText();}
    private String stock(String item,int quantity,String location)throws Exception{return data(call(post("/inventories"),Map.of("projectId",DEMO_PROJECT,"itemId",item,"quantity",quantity,"location",location))).path("inventoryId").asText();}
    private void assertStock(String id,int qty,int reserved){assertEquals(qty,jdbc.queryForObject("select quantity from inventory where inventory_id=?",Integer.class,id));assertEquals(reserved,jdbc.queryForObject("select reserved_quantity from inventory where inventory_id=?",Integer.class,id));}
    private ResultActions call(MockHttpServletRequestBuilder request,Map<String,?> body)throws Exception{request.header("Authorization","Bearer "+jwt.generateAccessToken(DEMO_OWNER));if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));return mvc.perform(request);}
    private JsonNode data(ResultActions result)throws Exception{return mapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");}
}
