package vn.nutrimom.admin.realtime;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminRealtimeRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public AdminRealtimeRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

    public String dashboardRevision() { return revision("DASHBOARD"); }
    public String notificationRevision(String user) { return revision("NOTIFICATIONS:"+user); }
    private String revision(String key) {
        return jdbc.query("select coalesce(max(revision),0) from app.admin_realtime_revisions where scope_key=:key",
                new MapSqlParameterSource("key",key),(rs,index)->rs.getString(1)).get(0);
    }

    /** One statement/snapshot for all distinct connected accounts, including permission and count. */
    public Map<String,Snapshot> snapshots(Collection<String> users) {
        if(users.isEmpty()) return Map.of();
        var rows=jdbc.query("""
                select u.id,coalesce(d.revision,0) dashboard_revision,coalesce(n.revision,0) notifications_revision,
                  (select count(*) from app.notifications x where x.user_id=u.id
                     and x.audience='ADMIN' and x.read_at is null) unread_count
                from app.users u
                left join app.admin_realtime_revisions d on d.scope_key='DASHBOARD'
                left join app.admin_realtime_revisions n on n.scope_key=concat('NOTIFICATIONS:',u.id)
                where u.id in (:users) and u.status='ACTIVE' and exists
                  (select 1 from app.user_roles r where r.user_id=u.id and r.role='ADMIN')
                """,new MapSqlParameterSource("users",users),(rs,index)->new Snapshot(1,rs.getString("id"),
                rs.getString("dashboard_revision"),rs.getString("notifications_revision"),rs.getLong("unread_count")));
        var result=new LinkedHashMap<String,Snapshot>();
        for(var row:rows) result.put(row.adminUserId(),row);
        return result;
    }
    public record Snapshot(int protocolVersion,String adminUserId,String dashboardRevision,
                           String notificationsRevision,long unreadCount) { }
}
