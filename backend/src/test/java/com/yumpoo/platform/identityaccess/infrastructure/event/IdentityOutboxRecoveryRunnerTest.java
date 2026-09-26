package com.yumpoo.platform.identityaccess.infrastructure.event;

import com.yumpoo.platform.foundation.application.outbox.OutboxStorePort;
import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleMaintenanceUseCase;
import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleMutationResult;
import com.yumpoo.platform.identityaccess.application.event.IdentityCommittedFactConsumer;
import com.yumpoo.platform.identityaccess.infrastructure.authorization.MaintenanceRoleRunner;
import com.yumpoo.platform.identityaccess.infrastructure.authorization.MaintenanceRoleRunnerProperties;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import com.yumpoo.platform.organization.api.CompanyConfigurationSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdentityOutboxRecoveryRunnerTest {

    @Test
    void recoveryFinishesBeforeMaintenanceClosesTheApplicationContext() {
        List<String> calls = new ArrayList<>();
        AtomicReference<ConfigurableApplicationContext> runningContext = new AtomicReference<>();
        OutboxStorePort store = mock(OutboxStorePort.class);
        when(store.requeueMissingConsumerEvents(any(), any())).thenAnswer(invocation -> {
            if (!runningContext.get().isActive()) {
                throw new IllegalStateException("outbox recovery accessed a closed application context");
            }
            calls.add("recovery");
            return 0;
        });
        PlatformRoleMaintenanceUseCase maintenance = mock(PlatformRoleMaintenanceUseCase.class);
        when(maintenance.execute(any())).thenAnswer(invocation -> {
            calls.add("maintenance");
            return mock(PlatformRoleMutationResult.class);
        });
        CompanyConfigurationQuery company = mock(CompanyConfigurationQuery.class);
        when(company.current()).thenReturn(new CompanyConfigurationSnapshot(
                UUID.randomUUID(), "Outbox startup test", ZoneOffset.UTC, DayOfWeek.MONDAY, 480, 0));

        SpringApplication application = new SpringApplication(MaintenanceStartup.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        application.addInitializers(context -> {
            runningContext.set(context);
            context.getBeanFactory().registerSingleton("outboxStore", store);
            context.getBeanFactory().registerSingleton("maintenanceUseCase", maintenance);
            context.getBeanFactory().registerSingleton("companyQuery", company);
        });

        try (ConfigurableApplicationContext context = application.run(
                "--yumpoo.maintenance.app-manager.enabled=true")) {
            assertThat(context.isActive()).isFalse();
        }

        assertThat(calls).hasSize(11);
        assertThat(calls.subList(0, 10)).containsOnly("recovery");
        assertThat(calls.getLast()).isEqualTo("maintenance");
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import({MaintenanceRoleRunner.class, IdentityCommittedFactConsumer.class, IdentityOutboxRecoveryRunner.class})
    static class MaintenanceStartup {

        @Bean
        MaintenanceRoleRunnerProperties maintenanceProperties() {
            return new MaintenanceRoleRunnerProperties(true, "BOOTSTRAP", UUID.randomUUID(), "startup test");
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
