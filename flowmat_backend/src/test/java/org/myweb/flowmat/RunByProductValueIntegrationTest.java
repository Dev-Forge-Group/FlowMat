package org.myweb.flowmat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Read-only value calculations; all data lives in the disposable Testcontainers database. */
@AutoConfigureMockMvc
class RunByProductValueIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtProvider jwt;

    @Test
    void valuesOnlyRecordedByProductsInItemUnitsWithoutSubtractingMaterialCost() throws Exception {
        String product=item("5"),by=item("4"),waste=item("99"),input=item("2");
        String bom=bom(product);line(bom,by,"by_product");line(bom,waste,"waste");
        String run=run(bom);record(run,by,"output","1500","g",false);record(run,by,"output","0.5","kg",false);
        record(run,by,"output","8","kg",true);record(run,by,"output",null,"kg",false);
        record(run,waste,"output","5","kg",false);record(run,product,"output","50","kg",false);
        record(run,input,"input","100","kg",false);
        read(run,DEMO_OWNER).andExpect(status().isOk()).andExpect(jsonPath("$.data.byProductValue").value(8))
            .andExpect(jsonPath("$.data.valueComplete").value(true)).andExpect(jsonPath("$.data.costBasis").value("CURRENT"))
            .andExpect(jsonPath("$.data.lines.length()").value(1)).andExpect(jsonPath("$.data.lines[0].quantity").value(2))
            .andExpect(jsonPath("$.data.lines[0].unit").value("kg"));
        mvc.perform(get("/production-runs/"+run+"/cost").header("Authorization","Bearer "+jwt.generateAccessToken(DEMO_OWNER)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.materialCost").value(200));
    }
    @Test
    void finishedValuesUseOriginalEndTimeAfterLaterPricesChange() throws Exception {
        String by=item("9"),bom=bom(item("1")),run=run(bom);line(bom,by,"by_product");record(run,by,"output","2","kg",false);
        history(by,"2029-01-01T00:00:00Z","1","2");history(by,"2031-01-01T00:00:00Z","2","9");
        jdbc.update("update production_run set run_status='finished',actual_end_at='2030-01-01T00:00:00Z' where production_run_id=?",run);
        jdbc.update("update bom_header set bom_status='retired' where bom_id=?",bom);
        read(run,DEMO_OWNER).andExpect(status().isOk()).andExpect(jsonPath("$.data.byProductValue").value(4))
            .andExpect(jsonPath("$.data.costBasis").value("HISTORICAL"))
            .andExpect(jsonPath("$.data.costBasisAt").value("2030-01-01T00:00:00Z"));
        jdbc.update("update item set unit_cost=20 where item_id=?",by);
        read(run,DEMO_OWNER).andExpect(jsonPath("$.data.byProductValue").value(4));
    }
    @Test
    void legacyFinishedRunIsEstimatedAndDeletedHistoricalItemsRemainVisible() throws Exception {
        String by=item("4"),bom=bom(item("1")),run=run(bom);line(bom,by,"by_product");record(run,by,"output","2","kg",false);
        jdbc.update("update production_run set run_status='finished' where production_run_id=?",run);
        jdbc.update("update item set deleted_yn='Y' where item_id=?",by);
        read(run,DEMO_OWNER).andExpect(status().isOk()).andExpect(jsonPath("$.data.byProductValue").value(8))
            .andExpect(jsonPath("$.data.estimated").value(true)).andExpect(jsonPath("$.data.costBasis").value("ESTIMATED"));
    }
    @Test
    void unknownPricesAndUnconvertibleUnitsDoNotBecomeZeroValue() throws Exception {
        String bom=bom(item("1")),run=run(bom),known=item("4"),unknown=item(null),badUnit=item("3");
        for(String id:new String[]{known,unknown,badUnit})line(bom,id,"by_product");
        record(run,known,"output","2","kg",false);record(run,unknown,"output","2","kg",false);record(run,badUnit,"output","1","ea",false);
        read(run,DEMO_OWNER).andExpect(status().isOk()).andExpect(jsonPath("$.data.byProductValue").value(8))
            .andExpect(jsonPath("$.data.valueComplete").value(false))
            .andExpect(jsonPath("$.data.lines[?(@.itemId=='"+unknown+"')].value").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())))
            .andExpect(jsonPath("$.data.lines[?(@.itemId=='"+badUnit+"')].quantity").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())));
    }
    @Test
    void foreignHistoricalItemsRevealNoMetadataAndReadAccessIsRequired() throws Exception {
        String by=item("4"),bom=bom(item("1")),run=run(bom);line(bom,by,"by_product");record(run,by,"output","2","kg",false);
        String foreign=UUID.randomUUID().toString();jdbc.update("insert into project(project_id,project_name,owner_id) values(?,'Other',?)",foreign,DEMO_OWNER);
        jdbc.update("update item set project_id=?,item_code='PRIVATE-CODE',item_name='Private name' where item_id=?",foreign,by);
        read(run,DEMO_OWNER).andExpect(status().isOk()).andExpect(jsonPath("$.data.valueComplete").value(false))
            .andExpect(jsonPath("$.data.lines[0].itemCode").value(by)).andExpect(jsonPath("$.data.lines[0].itemName").doesNotExist());
        read(run,user("viewer")).andExpect(status().isOk());read(run,user("outsider")).andExpect(status().isForbidden());
    }
    @Test
    void runWithoutBomHasNoClassifiedByProductsAndMissingRunsAre404() throws Exception {
        String run=run(null);record(run,item("3"),"output","2","kg",false);
        read(run,DEMO_OWNER).andExpect(status().isOk()).andExpect(jsonPath("$.data.byProductValue").value(0))
            .andExpect(jsonPath("$.data.lines").isEmpty());read("missing",DEMO_OWNER).andExpect(status().isNotFound());
    }
    @Test
    void knownHistoricalUnknownPriceStaysUnknownRatherThanUsingCurrentPrice() throws Exception {
        String by=item("9"),bom=bom(item("1")),run=run(bom);line(bom,by,"by_product");record(run,by,"output","2","kg",false);
        history(by,"2029-01-01T00:00:00Z","1",null);history(by,"2031-01-01T00:00:00Z",null,"9");
        jdbc.update("update production_run set run_status='finished',actual_end_at='2030-01-01T00:00:00Z' where production_run_id=?",run);
        read(run,DEMO_OWNER).andExpect(status().isOk()).andExpect(jsonPath("$.data.byProductValue").value(0))
            .andExpect(jsonPath("$.data.valueComplete").value(false)).andExpect(jsonPath("$.data.costBasis").value("HISTORICAL"))
            .andExpect(jsonPath("$.data.lines[0].unitCost").doesNotExist());
    }
    @Test
    void quantitiesAreAddedBeforeFourDecimalRounding() throws Exception {
        String by=item("10000"),bom=bom(item("1")),run=run(bom);line(bom,by,"by_product");
        record(run,by,"output","0.05","g",false);record(run,by,"output","0.05","g",false);
        read(run,DEMO_OWNER).andExpect(status().isOk()).andExpect(jsonPath("$.data.byProductValue").value(1))
            .andExpect(jsonPath("$.data.lines[0].quantity").value(0.0001));
    }
    @Test
    void aRunCannotReadOutputClassificationFromAnotherProjectsBom() throws Exception {
        String bom=bom(item("1")),run=run(bom),foreign=UUID.randomUUID().toString();
        jdbc.update("insert into project(project_id,project_name,owner_id) values(?,'Other BOM',?)",foreign,DEMO_OWNER);
        jdbc.update("update bom_header set project_id=? where bom_id=?",foreign,bom);
        read(run,DEMO_OWNER).andExpect(status().isNotFound());
        jdbc.update("update production_run set deleted_yn='Y' where production_run_id=?",run);
        read(run,DEMO_OWNER).andExpect(status().isNotFound());
    }
    private String item(String price){String id=UUID.randomUUID().toString();jdbc.update("insert into item(item_id,project_id,item_code,item_name,unit_id,unit_cost) values(?,?,?,?,?,?)",id,DEMO_PROJECT,"VAL-"+id.substring(0,8),"By-product", "unit_kg",price==null?null:new BigDecimal(price));return id;}
    private String bom(String product){String id=UUID.randomUUID().toString();jdbc.update("insert into bom_header(bom_id,project_id,target_item_id,bom_name,base_quantity,base_unit,bom_status) values(?,?,?,'Value test',1,'kg','approved')",id,DEMO_PROJECT,product);return id;}
    private void line(String bom,String item,String type){jdbc.update("insert into bom_line(bom_line_id,bom_id,child_item_id,quantity,unit,line_type) values(?,?,?,1,'kg',?)",UUID.randomUUID().toString(),bom,item,type);}
    private String run(String bom){String id=UUID.randomUUID().toString();jdbc.update("insert into production_run(production_run_id,project_id,workflow_id,run_number,run_type,run_status,bom_id,planned_output_qty,actual_output_qty) values(?,?,?,?,'simulation','running',?,2,2)",id,DEMO_PROJECT,DEMO_WORKFLOW,"VALUE-"+id.substring(0,8),bom);return id;}
    private void record(String run,String item,String direction,String quantity,String unit,boolean cancelled){jdbc.update("insert into production_run_item(production_run_item_id,production_run_id,item_id,direction,planned_qty,actual_qty,unit,cancelled_yn) values(?,?,?,?,0,?,?,?)",UUID.randomUUID().toString(),run,item,direction,quantity==null?null:new BigDecimal(quantity),unit,cancelled?"Y":"N");}
    private void history(String item,String at,String before,String after){jdbc.update("insert into item_cost_history(item_cost_history_id,project_id,item_id,previous_unit_cost,unit_cost,changed_by,changed_at) values(?,?,?,?,?,?,cast(? as timestamptz))",UUID.randomUUID().toString(),DEMO_PROJECT,item,before==null?null:new BigDecimal(before),after==null?null:new BigDecimal(after),DEMO_OWNER,at);}
    private String user(String role){String id=UUID.randomUUID().toString();jdbc.update("insert into users(user_id,user_name,user_email,user_pwd,user_birth,user_tel) select ?,user_name,?,user_pwd,user_birth,user_tel from users where user_id=?",id,id+"@test.local",DEMO_OWNER);if(!"outsider".equals(role))jdbc.update("insert into project_member(project_member_id,project_id,user_id,project_role) values(?,?,?,?)",UUID.randomUUID().toString(),DEMO_PROJECT,id,role);return id;}
    private ResultActions read(String run,String user)throws Exception{return mvc.perform(get("/production-runs/"+run+"/by-product-value").header("Authorization","Bearer "+jwt.generateAccessToken(user)));}
}
