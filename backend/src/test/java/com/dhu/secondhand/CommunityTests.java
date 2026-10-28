package com.dhu.secondhand;

import com.dhu.secondhand.community.CommunityService;
import com.dhu.secondhand.common.ApiException;
import com.dhu.secondhand.common.SessionAuth;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
        "app.jobs.enabled=false", "app.abuse.enabled=false", "app.mail.key-file=target/test-mail-code.key","spring.mail.username=test@example.com","spring.mail.test-connection=false",
        "spring.datasource.url=jdbc:h2:mem:community_test;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password="})
@AutoConfigureMockMvc
@Sql("/sql/community-schema.sql")
class CommunityTests {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CommunityService service;
    @Autowired private SessionAuth auth;
    @MockitoBean private JavaMailSender mailSender;

    @Test
    void completedTradePartiesReviewEachOtherAndReputationIsPublic() throws Exception {
        bodyPost("/api/reviews",2,Map.of("tradeId",20,"rating",5,"comment","交易顺利"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.revieweeId").value(1));
        bodyPost("/api/reviews",1,Map.of("tradeId",20,"rating",4,"comment","沟通及时"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.revieweeId").value(2));
        mvc.perform(get("/api/users/1/reputation")).andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewCount").value(1)).andExpect(jsonPath("$.averageRating").value(5));
        mvc.perform(get("/api/reviews").param("userId","1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].comment").value("交易顺利"));
    }

    @Test
    void reviewsRejectOutsiderIncompleteDuplicateAndAnonymous() throws Exception {
        Map<String,Object> review=Map.of("tradeId",20,"rating",5,"comment","评价");
        bodyPost("/api/reviews",3,review).andExpect(status().isForbidden());
        bodyPost("/api/reviews",2,Map.of("tradeId",21,"rating",5,"comment","评价")).andExpect(status().isConflict());
        bodyPost("/api/reviews",2,review).andExpect(status().isCreated());
        bodyPost("/api/reviews",2,review).andExpect(status().isConflict());
        mvc.perform(post("/api/reviews").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(review)))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(ints={0,6})
    void ratingMustBeOneToFive(int rating) throws Exception {
        bodyPost("/api/reviews",2,Map.of("tradeId",20,"rating",rating,"comment","评价"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentDuplicateReviewsProduceOneRecord() throws Exception {
        var executor=Executors.newFixedThreadPool(2);
        CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        try {
            var first=executor.submit(()->concurrentReview(ready,start));
            var second=executor.submit(()->concurrentReview(ready,start));
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();
            List<Integer> results=List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
            assertEquals(1,results.stream().filter(code->code==201).count());
            assertEquals(1,results.stream().filter(code->code==409).count());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM community_review",Integer.class));
        } finally {start.countDown();executor.shutdownNow();}
    }

    @Test
    void reportAppealAndAdminResolutionHaveAuditAndOwnership() throws Exception {
        long report=reportGoods(10);
        bodyPost("/api/reports/"+report+"/appeals",2,Map.of("reason","补充证据"))
                .andExpect(status().isConflict());
        bodyPut("/api/admin/reports/"+report+"/resolution",2,Map.of("decision","REJECTED","note","证据不足"))
                .andExpect(status().isForbidden());
        bodyPut("/api/admin/reports/"+report+"/resolution",4,Map.of("decision","REJECTED","note","证据不足"))
                .andExpect(status().isOk());
        bodyPost("/api/reports/"+report+"/appeals",3,Map.of("reason","补充证据"))
                .andExpect(status().isForbidden());
        long appeal=id(bodyPost("/api/reports/"+report+"/appeals",2,Map.of("reason","补充证据"))
                .andExpect(status().isCreated()));
        bodyPost("/api/reports/"+report+"/appeals",2,Map.of("reason","重复申诉"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/reports/"+report+"/appeals").session(session(3))).andExpect(status().isForbidden());
        bodyPut("/api/admin/appeals/"+appeal+"/resolution",4,Map.of("decision","UPHELD","note","准予重新核查"))
                .andExpect(status().isOk());
        assertEquals("OPEN",jdbc.queryForObject("SELECT status FROM community_report WHERE id=?",String.class,report));
        bodyPut("/api/admin/reports/"+report+"/resolution",4,
                Map.of("decision","RESOLVED","note","内容违规","action","HIDE_GOODS"))
                .andExpect(status().isOk());
        assertEquals("HIDDEN",jdbc.queryForObject("SELECT status FROM market_goods WHERE id=10",String.class));
        mvc.perform(get("/api/admin/reports/"+report+"/audit").session(session(4)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5));
        mvc.perform(get("/api/admin/reports").session(session(4)).param("status","RESOLVED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/reports/mine").session(session(3))).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void duplicateAndSelfReportsAndMissingTargetAreRejected() throws Exception {
        reportGoods(10);
        bodyPost("/api/reports",2,Map.of("targetType","GOODS","targetId",10,"reason","重复"))
                .andExpect(status().isConflict());
        bodyPost("/api/reports",1,Map.of("targetType","GOODS","targetId",10,"reason","自己"))
                .andExpect(status().isBadRequest());
        bodyPost("/api/reports",2,Map.of("targetType","GOODS","targetId",999,"reason","不存在"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cannotHideGoodsWhileTradingOrApplyWrongTargetAction() throws Exception {
        long report=reportGoods(11);
        bodyPut("/api/admin/reports/"+report+"/resolution",4,
                Map.of("decision","RESOLVED","note","核查","action","HIDE_GOODS"))
                .andExpect(status().isConflict());
        assertEquals("OPEN",jdbc.queryForObject("SELECT status FROM community_report WHERE id=?",String.class,report));
        bodyPut("/api/admin/reports/"+report+"/resolution",4,
                Map.of("decision","RESOLVED","note","核查","action","DISABLE_USER"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void disableAndRestoreAccountIsAdminOnlyAndProtectsAdminsAndCancelledAccounts() throws Exception {
        long report=id(bodyPost("/api/reports",2,Map.of("targetType","USER","targetId",3,"reason","恶意行为"))
                .andExpect(status().isCreated()));
        bodyPut("/api/admin/reports/"+report+"/resolution",4,
                Map.of("decision","RESOLVED","note","禁用账号","action","DISABLE_USER"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/favorites").session(session(3))).andExpect(status().isForbidden());
        bodyPut("/api/admin/users/3/status",2,Map.of("status",0)).andExpect(status().isForbidden());
        bodyPut("/api/admin/users/3/status",4,Map.of("status",0)).andExpect(status().isOk());
        mvc.perform(get("/api/favorites").session(session(3))).andExpect(status().isOk());
        bodyPut("/api/admin/users/4/status",4,Map.of("status",1)).andExpect(status().isForbidden());
        bodyPut("/api/admin/users/5/status",4,Map.of("status",0)).andExpect(status().isConflict());
        bodyPut("/api/admin/users/3/status",4,Map.of("status",2)).andExpect(status().isBadRequest());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM community_user_audit",Integer.class));
    }

    @Test
    void appealCannotBeResolvedTwiceAndRejectingCannotApplySanctions() throws Exception {
        long report=reportGoods(10);
        bodyPut("/api/admin/reports/"+report+"/resolution",4,
                Map.of("decision","REJECTED","note","不成立","action","HIDE_GOODS"))
                .andExpect(status().isBadRequest());
        bodyPut("/api/admin/reports/"+report+"/resolution",4,Map.of("decision","REJECTED","note","不成立"))
                .andExpect(status().isOk());
        long appeal=id(bodyPost("/api/reports/"+report+"/appeals",2,Map.of("reason","再次说明"))
                .andExpect(status().isCreated()));
        bodyPut("/api/admin/appeals/"+appeal+"/resolution",4,Map.of("decision","REJECTED","note","维持原处理"))
                .andExpect(status().isOk());
        bodyPut("/api/admin/appeals/"+appeal+"/resolution",4,Map.of("decision","UPHELD","note","重复"))
                .andExpect(status().isConflict());
    }

    @Test
    void favoriteIsIdempotentPrivateAndCanBeRemoved() throws Exception {
        mvc.perform(put("/api/favorites/10").session(session(2))).andExpect(status().isOk())
                .andExpect(jsonPath("$.goodsId").value(10));
        mvc.perform(put("/api/favorites/10").session(session(2))).andExpect(status().isOk());
        mvc.perform(get("/api/favorites").session(session(2))).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/favorites").session(session(3))).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(put("/api/favorites/11").session(session(2))).andExpect(status().isConflict());
        mvc.perform(delete("/api/favorites/10").session(session(2))).andExpect(status().isNoContent());
        mvc.perform(delete("/api/favorites/10").session(session(2))).andExpect(status().isNoContent());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM community_favorite",Integer.class));
    }

    @Test
    void wantedSupportsSearchTextbookCategoryAndOwnerClose() throws Exception {
        long wanted=id(bodyPost("/api/wanted",2,Map.of("title","求购高数教材","description","希望八成新","maxPrice",30,"category","TEXTBOOK"))
                .andExpect(status().isCreated()));
        mvc.perform(get("/api/wanted").param("q","高数").param("category","TEXTBOOK"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/wanted").param("q","%"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(put("/api/wanted/"+wanted+"/close").session(session(3))).andExpect(status().isForbidden());
        mvc.perform(put("/api/wanted/"+wanted+"/close").session(session(2))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        mvc.perform(get("/api/wanted")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void paginationAndWantedValidationRejectInvalidValues() throws Exception {
        mvc.perform(get("/api/wanted").param("size","51")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/reviews").param("userId","1").param("page","0")).andExpect(status().isBadRequest());
        bodyPost("/api/wanted",2,Map.of("title","求购","description","说明","maxPrice",-1))
                .andExpect(status().isBadRequest());
        bodyPost("/api/wanted",2,Map.of("title","求购","description","说明","maxPrice",1.001))
                .andExpect(status().isBadRequest());
        bodyPost("/api/wanted",2,Map.of("title"," ","description","说明","maxPrice",1))
                .andExpect(status().isBadRequest());
    }

    @Test
    void transactionLayerRejectsAccountClosedAfterSessionCheck() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session(3));
        long actor = auth.requireUserId(request);
        jdbc.update("UPDATE app_user SET status=2 WHERE id=?", actor);
        ApiException exception = assertThrows(ApiException.class,
                () -> service.wanted(actor,"求购教材","说明",new java.math.BigDecimal("20"),"TEXTBOOK"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM community_wanted",Integer.class));
        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN,exception.getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings={"\u2003","\u00a0","\u200b"})
    void unicodeBlankTextIsRejected(String blank) throws Exception {
        bodyPost("/api/wanted",2,Map.of("title",blank,"description","说明","maxPrice",1))
                .andExpect(status().isBadRequest());
        bodyPost("/api/reviews",2,Map.of("tradeId",20,"rating",5,"comment",blank))
                .andExpect(status().isBadRequest());
        bodyPost("/api/reports",2,Map.of("targetType","GOODS","targetId",10,"reason",blank))
                .andExpect(status().isBadRequest());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM community_report",Integer.class));
    }

    @Test
    void cancelledAndDisabledUsersWantedPostsAreNotPublic() throws Exception {
        bodyPost("/api/wanted",2,Map.of("title","求购教材","description","说明","maxPrice",20))
                .andExpect(status().isCreated());
        jdbc.update("UPDATE app_user SET status=2 WHERE id=2");
        mvc.perform(get("/api/wanted")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void hiddenGoodsAndDisabledSellerAreExcludedFromFavorites() throws Exception {
        mvc.perform(put("/api/favorites/10").session(session(2))).andExpect(status().isOk());
        bodyPut("/api/admin/users/1/status",4,Map.of("status",1)).andExpect(status().isOk());
        mvc.perform(get("/api/favorites").session(session(2))).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(put("/api/favorites/10").session(session(2))).andExpect(status().isConflict());
        bodyPut("/api/admin/users/1/status",4,Map.of("status",0)).andExpect(status().isOk());
        jdbc.update("UPDATE market_goods SET status='HIDDEN' WHERE id=10");
        mvc.perform(get("/api/favorites").session(session(2))).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.items.length()").value(0));
    }

    private int concurrentReview(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if(!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
        return bodyPost("/api/reviews",2,Map.of("tradeId",20,"rating",5,"comment","评价"))
                .andReturn().getResponse().getStatus();
    }

    private long reportGoods(long goods) throws Exception {
        return id(bodyPost("/api/reports",2,Map.of("targetType","GOODS","targetId",goods,"reason","商品描述有误"))
                .andExpect(status().isCreated()));
    }

    private long id(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsByteArray()).path("id").asLong();
    }

    private MockHttpSession session(long user) {
        MockHttpSession session=new MockHttpSession();session.setAttribute("userId",user);
        Integer version=jdbc.queryForObject("SELECT session_version FROM app_user WHERE id=?",Integer.class,user);
        session.setAttribute("sessionVersion",version);return session;
    }

    private ResultActions bodyPost(String url,long actor,Object body) throws Exception {
        return mvc.perform(post(url).session(session(actor)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions bodyPut(String url,long actor,Object body) throws Exception {
        return mvc.perform(put(url).session(session(actor)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
}
