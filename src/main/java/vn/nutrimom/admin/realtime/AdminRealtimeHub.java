package vn.nutrimom.admin.realtime;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vn.nutrimom.admin.realtime.AdminRealtimeRepository.Snapshot;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;

/** Bounded connections and one queued send per client; socket writes never occupy the polling thread. */
public class AdminRealtimeHub {
    private static final Logger log=LoggerFactory.getLogger(AdminRealtimeHub.class);
    private final AdminRealtimeRepository repository;
    private final AdminRealtimeProperties properties;
    private final Clock clock;
    private final Executor sender;
    private final ConcurrentHashMap<UUID,Client> clients=new ConcurrentHashMap<>();
    private final AtomicBoolean polling=new AtomicBoolean();
    private volatile boolean shuttingDown;

    public AdminRealtimeHub(AdminRealtimeRepository repository,AdminRealtimeProperties properties,
                            Clock clock,Executor sender) {
        this.repository=repository;this.properties=properties;this.clock=clock;this.sender=sender;
    }
    public SseEmitter open(String user,Instant tokenExpiresAt) {
        Instant now=clock.instant();
        if(tokenExpiresAt==null || !tokenExpiresAt.isAfter(now)) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        Snapshot snapshot=repository.snapshots(java.util.Set.of(user)).get(user);
        if(snapshot==null) throw new BusinessException(ErrorCode.FORBIDDEN);
        Instant deadline=tokenExpiresAt.isBefore(now.plusSeconds(300))?tokenExpiresAt:now.plusSeconds(300);
        var client=new Client(user,deadline,newEmitter(Math.max(1,deadline.toEpochMilli()-now.toEpochMilli())),snapshot,now);
        client.sending.set(true); // ready must precede any poll-driven update, including at registration races.
        synchronized(clients) {
            if(shuttingDown || clients.size()>=properties.getMaxConnections()
                    || clients.values().stream().filter(c->c.user.equals(user)).count()>=properties.getMaxConnectionsPerUser())
                throw new BusinessException(ErrorCode.RATE_LIMITED,"Đã đạt giới hạn kết nối cập nhật Admin.");
            clients.put(client.id,client);
        }
        client.emitter.onCompletion(()->remove(client));
        client.emitter.onTimeout(()->finish(client));
        client.emitter.onError(error->remove(client));
        try { send(client,"ready",snapshot); }
        catch(IOException | RuntimeException failure) { finish(client); throw new IllegalStateException("Cannot initialize Admin stream",failure); }
        finally { client.sending.set(false); }
        return client.emitter;
    }
    protected SseEmitter newEmitter(long timeout) { return new SseEmitter(timeout); }

    public void tick() {
        if(clients.isEmpty() || !polling.compareAndSet(false,true)) return;
        try {
            var now=clock.instant();
            var batch=java.util.List.copyOf(clients.values());
            for(var client:batch) if(!now.isBefore(client.deadline)) finish(client);
            var users=batch.stream().filter(c->!c.closed.get()).map(c->c.user).collect(java.util.stream.Collectors.toSet());
            if(users.isEmpty()) return;
            var snapshots=repository.snapshots(users);
            for(var client:batch) {
                if(client.closed.get()) continue;
                Snapshot snapshot=snapshots.get(client.user);
                if(snapshot!=null && snapshot.equals(client.last) && now.isBefore(client.lastSent.plusSeconds(15))) continue;
                if(!client.sending.compareAndSet(false,true)) continue;
                try { sender.execute(()->deliver(client,snapshot)); }
                catch(RejectedExecutionException failure) { client.sending.set(false); finish(client); }
            }
        } catch(RuntimeException failure) {
            // A disconnected DB cannot keep a stale stream looking healthy indefinitely.
            log.warn("Admin stream polling failed error={}",failure.getClass().getSimpleName());
            for(var client:clients.values()) finish(client);
        } finally { polling.set(false); }
    }
    private void deliver(Client client,Snapshot snapshot) {
        try {
            if(client.closed.get()) return;
            Instant now=clock.instant();
            if(!now.isBefore(client.deadline)) { finish(client); return; }
            if(snapshot==null) {
                send(client,"access_revoked",Map.of("protocol_version",1,"reason","ADMIN_ACCESS_REVOKED"));
                finish(client);return;
            }
            if(!snapshot.equals(client.last)) {
                send(client,"update",snapshot);client.last=snapshot;client.lastSent=now;
            } else if(!now.isBefore(client.lastSent.plusSeconds(15))) {
                send(client,"heartbeat",Map.of("protocol_version",1));client.lastSent=now;
            }
        } catch(IOException | RuntimeException failure) { finish(client); }
        finally { client.sending.set(false); }
    }
    private void send(Client client,String event,Object payload) throws IOException {
        client.emitter.send(SseEmitter.event().name(event).data(payload,MediaType.APPLICATION_JSON));
    }
    private void remove(Client client) { client.closed.set(true);clients.remove(client.id,client); }
    private void finish(Client client) {
        if(!client.closed.compareAndSet(false,true)) return;
        clients.remove(client.id,client);
        try { client.emitter.complete(); }
        catch(RuntimeException ignored) { /* Container may already have closed a disconnected response. */ }
    }
    public int connectionCount() { return clients.size(); }
    @PreDestroy public void shutdown() {
        synchronized(clients) { shuttingDown=true; }
        for(var client:clients.values()) finish(client);
    }
    private static final class Client {
        final UUID id=UUID.randomUUID();final String user;final Instant deadline;final SseEmitter emitter;
        final AtomicBoolean closed=new AtomicBoolean();final AtomicBoolean sending=new AtomicBoolean();
        volatile Snapshot last;volatile Instant lastSent;
        Client(String user,Instant deadline,SseEmitter emitter,Snapshot last,Instant now) {
            this.user=user;this.deadline=deadline;this.emitter=emitter;this.last=last;this.lastSent=now;
        }
    }
}
