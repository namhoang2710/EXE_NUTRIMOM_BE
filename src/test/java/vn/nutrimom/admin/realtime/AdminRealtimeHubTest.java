package vn.nutrimom.admin.realtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vn.nutrimom.admin.realtime.AdminRealtimeRepository.Snapshot;
import vn.nutrimom.common.exception.BusinessException;

class AdminRealtimeHubTest {
    final AdminRealtimeRepository repository=mock(AdminRealtimeRepository.class);
    final AdminRealtimeProperties properties=new AdminRealtimeProperties();
    final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-10-15T05:00:00Z"));
    final Map<String,Snapshot> data=new HashMap<>();
    final List<SseEmitter> emitters=new ArrayList<>();
    final List<String> wire=new ArrayList<>();
    final Clock clock=mock(Clock.class);
    AdminRealtimeHub hub;
    @BeforeEach void setup() throws Exception {
        when(clock.instant()).thenAnswer(call->now.get());
        data.put("a",new Snapshot(1,"a","1","1",0));data.put("b",new Snapshot(1,"b","1","1",0));
        when(repository.snapshots(anyCollection())).thenAnswer(call->{
            Map<String,Snapshot> result=new HashMap<>();
            for(String user:call.<Collection<String>>getArgument(0)) if(data.containsKey(user))result.put(user,data.get(user));
            return result;
        });
        hub=hub(Runnable::run);
    }
    AdminRealtimeHub hub(java.util.concurrent.Executor executor) {
        return new AdminRealtimeHub(repository,properties,clock,executor) {
            @Override protected SseEmitter newEmitter(long timeout) {
                var emitter=mock(SseEmitter.class);emitters.add(emitter);
                try { doAnswer(call->{
                    var frame=call.<SseEmitter.SseEventBuilder>getArgument(0).build();
                    wire.add(frame.stream().map(part->String.valueOf(part.getData())).collect(java.util.stream.Collectors.joining()));
                    return null;
                }).when(emitter).send(any(SseEmitter.SseEventBuilder.class)); }
                catch(java.io.IOException failure) { throw new AssertionError(failure); }
                return emitter;
            }
        };
    }
    void open(String user) {hub.open(user,now.get().plusSeconds(900));}
    @Test void pendingCountChangesPushUpdatesWithoutNotificationOrDashboardChanges() {
        open("a"); wire.clear();
        data.put("a",new Snapshot(1,"a","1","1",0,3));
        hub.tick(); assertEquals(1,wire.size()); assertTrue(wire.get(0).contains("event:update"));
        assertTrue(wire.get(0).contains("communityPendingCount=3"));
        data.put("a",new Snapshot(1,"a","1","1",0,1));
        hub.tick(); assertEquals(2,wire.size()); assertTrue(wire.get(1).contains("communityPendingCount=1"));
        hub.tick(); assertEquals(2,wire.size());
    }

    @Test void idleHasNoQueriesAndAllConnectionsShareOneSnapshotQueryPerTick() {
        hub.tick();verifyNoInteractions(repository);
        open("a");open("a");open("b");wire.clear();clearInvocations(repository);
        hub.tick();assertTrue(wire.isEmpty());verify(repository).snapshots(Set.of("a","b"));
        now.set(now.get().plusSeconds(16));hub.tick();
        assertEquals(3,wire.size());assertTrue(wire.stream().allMatch(s->s.contains("event:heartbeat")));
    }
    @Test void changesAreScopedAndDuplicateSnapshotsDoNotSendAnotherUpdate() {
        open("a");open("b");wire.clear();
        data.put("a",new Snapshot(1,"a","2","9007199254740993",2));
        hub.tick();assertEquals(1,wire.size());assertTrue(wire.get(0).contains("event:update"));
        assertTrue(wire.get(0).contains("9007199254740993"));
        hub.tick();assertEquals(1,wire.size());
    }
    @Test void revokedAccountReceivesTerminalEventAndTokenExpiryStopsConnection() {
        open("a");open("b");wire.clear();data.remove("a");hub.tick();
        assertEquals(1,hub.connectionCount());assertTrue(wire.get(0).contains("access_revoked"));
        verify(emitters.get(0)).complete();
        now.set(now.get().plusSeconds(300));hub.tick();assertEquals(0,hub.connectionCount());
        verify(emitters.get(1)).complete();
    }
    @Test void accountAndInstanceCapsAndExpiredTokenAreEnforced() {
        properties.setMaxConnections(2);properties.setMaxConnectionsPerUser(1);
        assertThrows(BusinessException.class,()->hub.open("a",now.get()));verifyNoInteractions(repository);
        open("a");assertThrows(BusinessException.class,()->open("a"));open("b");
        data.put("c",new Snapshot(1,"c","1","0",0));assertThrows(BusinessException.class,()->open("c"));
        assertEquals(2,hub.connectionCount());hub.shutdown();assertEquals(0,hub.connectionCount());
        assertThrows(BusinessException.class,()->open("a"));
    }
    @Test void atMostOneSendIsQueuedPerClientAndLatestStateIsReconciledNextTick() {
        var jobs=new ArrayList<Runnable>();hub=hub(jobs::add);open("a");wire.clear();
        data.put("a",new Snapshot(1,"a","2","1",0));hub.tick();
        data.put("a",new Snapshot(1,"a","3","1",0));for(int i=0;i<10;i++)hub.tick();
        assertEquals(1,jobs.size());jobs.remove(0).run();hub.tick();assertEquals(1,jobs.size());jobs.remove(0).run();
        assertEquals(2,wire.size());assertTrue(wire.get(1).contains("dashboardRevision=3"));
    }
    @Test void unavailableDatabaseAndSaturatedSenderReleaseConnections() {
        open("a");when(repository.snapshots(anyCollection())).thenThrow(new IllegalStateException("DB QA"));
        hub.tick();assertEquals(0,hub.connectionCount());verify(emitters.get(0)).complete();
    }
    @Test void saturatedQueueClosesClientInsteadOfBlockingOrAccumulatingMessages() {
        hub=hub(task->{throw new RejectedExecutionException();});open("a");data.remove("a");hub.tick();assertEquals(0,hub.connectionCount());
    }
    @Test void aConnectionRegisteredDuringPollIsNotMistakenForRevokedAccess() {
        open("a");wire.clear();var registered=new java.util.concurrent.atomic.AtomicBoolean();
        when(repository.snapshots(anyCollection())).thenAnswer(call->{
            var ids=call.<Collection<String>>getArgument(0);
            if(registered.compareAndSet(false,true))open("b");
            Map<String,Snapshot> result=new HashMap<>();for(String id:ids)result.put(id,data.get(id));return result;
        });
        hub.tick();assertEquals(2,hub.connectionCount());assertFalse(wire.stream().anyMatch(s->s.contains("access_revoked")));
    }
}
