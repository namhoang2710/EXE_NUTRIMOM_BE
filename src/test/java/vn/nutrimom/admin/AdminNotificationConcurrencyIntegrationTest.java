package vn.nutrimom.admin;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.nutrimom.auth.domain.*;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.notification.domain.NotificationType;
import vn.nutrimom.notification.repository.NotificationRepository;
import vn.nutrimom.notification.service.NotificationService;

@SpringBootTest
@ActiveProfiles("test")
class AdminNotificationConcurrencyIntegrationTest {
    @Autowired UserRepository users;
    @Autowired NotificationRepository notifications;
    @Autowired NotificationService publisher;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean vn.nutrimom.assistant.service.GroqAssistantClient groq;
    @MockitoBean vn.nutrimom.consultation.video.LiveKitClient livekit;
    final List<String> owners=new ArrayList<>();
    TransactionTemplate tx;
    @BeforeEach void setup() {
        tx=new TransactionTemplate(manager);
        for(int i=0;i<2;i++)owners.add(tx.execute(s->{
            var u=new UserEntity();u.setDisplayName("Concurrent Admin");u.setPhone("test-"+UUID.randomUUID().toString().substring(0,12));
            u.setStatus(UserStatus.ACTIVE);u.setRoles(Set.of(UserRole.ADMIN));return users.saveAndFlush(u).getId();
        }));
    }
    @AfterEach void cleanup() {
        tx.executeWithoutResult(s->{for(String owner:owners){jdbc.update("delete from app.notifications where user_id=?",owner);users.deleteById(owner);}});
    }
    @Test void parallelDuplicateEventsCommitOncePerRecipientWithoutRollingBackOtherWork() throws Exception {
        String source=UUID.randomUUID().toString();var ready=new CountDownLatch(4);var go=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(4);
        try {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<4;i++) {
                String owner=owners.get(i%2);
                futures.add(pool.submit(()->{
                    ready.countDown();try{go.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
                    publisher.publishAdminOnce(owner,NotificationType.CONSULTATION,"Waiting","Safe metadata",
                            "nutrimom://admin/appointments/"+source,"ADMIN_EXPERT_WAITING",source);
                }));
            }
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();
            for(var future:futures)future.get(15,TimeUnit.SECONDS);
            for(String owner:owners)assertEquals(1,notifications.countByUserIdAndReadAtIsNull(owner));
            tx.executeWithoutResult(s->{var item=notifications.findByDedupKey("ADMIN_EXPERT_WAITING:"+owners.get(0)+":"+source).orElseThrow();
                publisher.markRead(owners.get(0),item.getId());});
            assertEquals(0,notifications.countByUserIdAndReadAtIsNull(owners.get(0)));
            assertEquals(1,notifications.countByUserIdAndReadAtIsNull(owners.get(1)));
        } finally {go.countDown();pool.shutdownNow();pool.awaitTermination(5,TimeUnit.SECONDS);}
    }
}
