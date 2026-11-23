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

@AutoConfigureMockMvc
class AllocatedStockTransferIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtProvider jwt;
    @Autowired JdbcTemplate jdbc;

    @Test
    void partialAllocatedMoveKeepsReservationAndSplitsAllocationInOneTransaction() throws Exception {
        var fixture = fixture(); var input = input(fixture, 3);
        JsonNode moved = data(call(post("/allocated-stock-transfers"), input));
        String to = moved.path("in").path("inventoryId").asText();
        assertStock(fixture.stock(), 7, 3); assertStock(to, 3, 3);
        assertEquals(3, jdbc.queryForObject("select quantity-consumed_quantity-released_quantity from stock_allocation where allocation_id=?", Integer.class, fixture.allocation()));
        assertEquals(3, jdbc.queryForObject("select sum(quantity-consumed_quantity-released_quantity) from stock_allocation where work_order_id=? and inventory_id=?", Integer.class, fixture.order(), to));
        call(post("/allocated-stock-transfers"), input).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.transferId").value(moved.path("transferId").asText()));
        assertStock(fixture.stock(), 7, 3); assertStock(to, 3, 3);
        call(post("/allocated-stock-transfers"), Map.of("projectId", DEMO_PROJECT, "workOrderId", fixture.order(),
            "allocationId", fixture.allocation(), "fromInventoryId", fixture.stock(), "toLocation", "OTHER-BIN",
            "quantity", 3, "requestId", input.get("requestId"))).andExpect(status().isConflict());
    }
    @Test
    void fullMoveKeepsAllocationIdentityAndRetryDoesNotMoveItAgain() throws Exception {
        var fixture=fixture(); var input=input(fixture,6);
        JsonNode result=data(call(post("/allocated-stock-transfers"),input));
        assertStock(fixture.stock(),4,0);assertStock(result.path("in").path("inventoryId").asText(),6,6);
        assertEquals(result.path("in").path("inventoryId").asText(),jdbc.queryForObject("select inventory_id from stock_allocation where allocation_id=?",String.class,fixture.allocation()));
        call(post("/allocated-stock-transfers"),input).andExpect(status().isOk());
        assertEquals(2,jdbc.queryForObject("select count(*) from inventory_transaction where reference_id=?",Integer.class,result.path("transferId").asText()));
    }
    @Test
    void oversizedAndQuarantinedMovesDoNotReleaseAnyReservedStock() throws Exception {
        var fixture=fixture();call(post("/allocated-stock-transfers"),input(fixture,7)).andExpect(status().isConflict());
        jdbc.update("update inventory set inventory_status='quarantined' where inventory_id=?",fixture.stock());
        call(post("/allocated-stock-transfers"),input(fixture,3)).andExpect(status().isConflict());
        assertStock(fixture.stock(),10,6);
        assertEquals(1,jdbc.queryForObject("select count(*) from stock_allocation where work_order_id=?",Integer.class,fixture.order()));
    }
    @Test
    void aDifferentWorkOrderCannotMoveThisAllocation() throws Exception {
        var fixture=fixture();var other=fixture();var body=new java.util.HashMap<>(input(fixture,2));body.put("workOrderId",other.order());
        call(post("/allocated-stock-transfers"),body).andExpect(status().isNotFound());assertStock(fixture.stock(),10,6);
    }
    @Test
    void allocationSaveFailureRollsBackBothTransferLegs() throws Exception {
        // Another order may already have a split of three; failure injection belongs only to this fixture.
        var unrelated=fixture();
        JsonNode unrelatedMove=data(call(post("/allocated-stock-transfers"),input(unrelated,3)));
        var fixture=fixture();jdbc.execute("alter table stock_allocation add constraint test_allocated_move_rollback check (work_order_id <> '"
            + fixture.order().replace("'", "''") + "' or quantity<>3)");
        try {
            call(post("/allocated-stock-transfers"),input(fixture,3)).andExpect(status().isConflict());
            assertStock(fixture.stock(),10,6);
            assertEquals(0,jdbc.queryForObject("select count(*) from inventory where item_id=? and location='PICK-STAGING'",Integer.class,fixture.item()));
            assertStock(unrelated.stock(),7,3);
            assertStock(unrelatedMove.path("in").path("inventoryId").asText(),3,3);
        } finally {jdbc.execute("alter table stock_allocation drop constraint test_allocated_move_rollback");}
    }
    @Test
    void consumedSourceHistoryRemainsAtSourceWhenItsRemainingReservationMoves() throws Exception {
        var fixture=fixture();
        jdbc.update("update stock_allocation set consumed_quantity=2 where allocation_id=?",fixture.allocation());
        jdbc.update("update inventory set quantity=8,reserved_quantity=4,available_quantity=4 where inventory_id=?",fixture.stock());
        var moved=data(call(post("/allocated-stock-transfers"),input(fixture,4)));
        assertStock(fixture.stock(),4,0);assertStock(moved.path("in").path("inventoryId").asText(),4,4);
        assertEquals("closed",jdbc.queryForObject("select status from stock_allocation where allocation_id=?",String.class,fixture.allocation()));
        assertEquals(2,jdbc.queryForObject("select consumed_quantity from stock_allocation where allocation_id=?",Integer.class,fixture.allocation()));
        assertEquals(fixture.stock(),jdbc.queryForObject("select inventory_id from stock_allocation where allocation_id=?",String.class,fixture.allocation()));
    }
    @Test
    void viewerAndOutsiderCannotMoveAndMalformedRequestsAre400() throws Exception {
        var fixture=fixture();
        for(String role:List.of("viewer","outsider")) {
            String actor="allocated-"+UUID.randomUUID().toString().substring(0,8);
            jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?",actor,actor+"@test.local",DEMO_OWNER);
            if(role.equals("viewer"))jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",UUID.randomUUID().toString(),DEMO_PROJECT,actor,role);
            mvc.perform(post("/allocated-stock-transfers").header("Authorization","Bearer "+jwt.generateAccessToken(actor))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(input(fixture,2)))).andExpect(status().isForbidden());
        }
        var body=new java.util.HashMap<>(input(fixture,2));body.put("requestId","bad uuid");call(post("/allocated-stock-transfers"),body).andExpect(status().isBadRequest());
        body=new java.util.HashMap<>(input(fixture,2));body.put("quantity",new java.math.BigDecimal("0.00001"));call(post("/allocated-stock-transfers"),body).andExpect(status().isBadRequest());
        assertStock(fixture.stock(),10,6);
    }
    @Test
    @org.junit.jupiter.api.Timeout(45)
    void concurrentRetryMovesTheReservedStockOnce() throws Exception {
        var fixture=fixture();var body=input(fixture,3);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->call(post("/allocated-stock-transfers"),body).andReturn().getResponse().getStatus());
            var second=pool.submit(()->call(post("/allocated-stock-transfers"),body).andReturn().getResponse().getStatus());
            assertEquals(200,first.get(15,java.util.concurrent.TimeUnit.SECONDS));assertEquals(200,second.get(15,java.util.concurrent.TimeUnit.SECONDS));
            assertStock(fixture.stock(),7,3);
            assertEquals(2,jdbc.queryForObject("select count(*) from inventory_transaction where item_id=? and reference_type='inventory_transfer'",Integer.class,fixture.item()));
        } finally {pool.shutdownNow();}
    }
    @Test
    void anOrdinaryTransferCannotImpersonateAnAllocatedMoveReplay() throws Exception {
        var fixture=fixture();var body=input(fixture,2);String requestId=body.get("requestId").toString();
        String note="Allocated pick|"+fixture.order()+"|"+fixture.allocation()+"|"+fixture.stock()+"|pick-staging";
        data(call(post("/inventory-transfers"),Map.of("fromInventoryId",fixture.stock(),"toLocation","PICK-STAGING",
            "quantity",2,"requestId","allocated-pick:"+requestId,"note",note)));
        call(post("/allocated-stock-transfers"),body).andExpect(status().isConflict());
        assertStock(fixture.stock(),8,6);
        assertEquals(6,jdbc.queryForObject("select quantity from stock_allocation where allocation_id=?",Integer.class,fixture.allocation()));
    }
    private record Fixture(String stock,String item,String order,String allocation){}
    private Fixture fixture() throws Exception {
        String item=data(call(post("/items"),Map.of("projectId",DEMO_PROJECT,"itemCode","AP-"+UUID.randomUUID(),"itemName","Allocated pick","unitId","unit_kg"))).path("itemId").asText();
        String stock=data(call(post("/inventories"),Map.of("projectId",DEMO_PROJECT,"itemId",item,"quantity",10,"location","PICK-SOURCE"))).path("inventoryId").asText();
        String order=data(call(post("/work-orders"),Map.of("projectId",DEMO_PROJECT,"workOrderTitle","Allocated pick","targetItemId",item,"targetQuantity",10))).path("workOrderId").asText();
        data(call(post("/work-orders/"+order+"/approve"),null));
        String allocation=data(call(post("/work-orders/"+order+"/allocations"),Map.of("lines",List.of(Map.of("itemId",item,"quantity",6))))).path("allocations").get(0).path("allocationId").asText();
        return new Fixture(stock,item,order,allocation);
    }
    private Map<String,Object> input(Fixture f,int quantity){return Map.of("projectId",DEMO_PROJECT,"workOrderId",f.order(),"allocationId",f.allocation(),"fromInventoryId",f.stock(),"toLocation","PICK-STAGING","quantity",quantity,"requestId",UUID.randomUUID().toString());}
    private void assertStock(String id,int quantity,int reserved){assertEquals(quantity,jdbc.queryForObject("select quantity from inventory where inventory_id=?",Integer.class,id));assertEquals(reserved,jdbc.queryForObject("select reserved_quantity from inventory where inventory_id=?",Integer.class,id));}
    private ResultActions call(MockHttpServletRequestBuilder request,Map<String,?> body)throws Exception{request.header("Authorization","Bearer "+jwt.generateAccessToken(DEMO_OWNER));if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));return mvc.perform(request);}
    private JsonNode data(ResultActions result)throws Exception{return mapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");}
}
