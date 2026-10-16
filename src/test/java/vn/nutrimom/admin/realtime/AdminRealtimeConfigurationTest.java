package vn.nutrimom.admin.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import vn.nutrimom.admin.web.AdminRealtimeController;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.config.AdminRealtimeConfiguration;

class AdminRealtimeConfigurationTest {
    final ApplicationContextRunner context=new ApplicationContextRunner()
            .withBean(AdminRealtimeRepository.class,()->mock(AdminRealtimeRepository.class))
            .withBean(Clock.class,Clock::systemUTC).withUserConfiguration(AdminRealtimeConfiguration.class);
    @Test void defaultOffDoesNotStartAnyStreamClockOrSender() {
        context.run(app->{assertThat(app).doesNotHaveBean(AdminRealtimeHub.class);
            assertThat(app).doesNotHaveBean("adminRealtimePoller");assertThat(app).doesNotHaveBean("adminRealtimeSender");});
    }
    @Test void enablingCreatesOneBoundedHubAndBothOwnedExecutors() {
        context.withPropertyValues("app.admin.realtime.enabled=true").run(app->{
            assertThat(app).hasSingleBean(AdminRealtimeHub.class);assertThat(app).hasBean("adminRealtimePoller");
            assertThat(app).hasBean("adminRealtimeSender");assertThat(app.getBean(AdminRealtimeHub.class).connectionCount()).isZero();
            verifyNoInteractions(app.getBean(AdminRealtimeRepository.class));
        });
    }
    @Test void disabledEndpointIsExplicitlyNotFoundForFrontendFallback() {
        var controller=new AdminRealtimeController(new StaticListableBeanFactory().getBeanProvider(AdminRealtimeHub.class));
        var error=assertThrows(BusinessException.class,()->controller.stream(null,new MockHttpServletRequest(),new MockHttpServletResponse()));
        assertThat(error.getStatus().value()).isEqualTo(404);
    }
}
