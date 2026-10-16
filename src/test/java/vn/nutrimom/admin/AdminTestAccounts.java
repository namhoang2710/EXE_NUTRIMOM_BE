package vn.nutrimom.admin;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import vn.nutrimom.auth.domain.*;
import vn.nutrimom.auth.repository.UserRepository;

/** Persist legacy fixture principals now that every admin request checks the actual account. */
public final class AdminTestAccounts {
    private static final AtomicInteger sequence = new AtomicInteger(80000);
    private AdminTestAccounts() {}
    public static String ensure(UserRepository users,String id) {
        if(users.findById(id).isPresent()) return id;
        var admin = new UserEntity();
        admin.setId(id); admin.setPhone("+84988"+sequence.incrementAndGet());
        admin.setDisplayName("Fixture Admin"); admin.setStatus(UserStatus.ACTIVE);
        admin.setRoles(Set.of(UserRole.ADMIN)); users.saveAndFlush(admin);
        return id;
    }
}
