package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.administration.api.CompanyController;
import com.yumpoo.platform.identityaccess.application.administration.IdentityMemberView;
import com.yumpoo.platform.identityaccess.application.administration.WeComConfigurationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class M111IdentityAdministrationContractTest {

    @Test
    void publishesCanonicalIdentityAdministrationPaths() {
        List<String> gets = Arrays.stream(IdentityAdministrationController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class))
                .flatMap(method -> Arrays.stream(method.getAnnotation(GetMapping.class).value()))
                .sorted()
                .toList();
        List<String> posts = Arrays.stream(IdentityAdministrationController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class))
                .flatMap(method -> Arrays.stream(method.getAnnotation(PostMapping.class).value()))
                .toList();

        assertThat(gets).containsExactly(
                "/admin/directory-sync-runs",
                "/admin/directory-sync-runs/{runId}",
                "/admin/directory-sync-runs/{runId}/failures",
                "/admin/integrations/wecom/status",
                "/admin/members",
                "/admin/members/{userId}");
        assertThat(posts).containsExactly("/admin/directory-sync-runs");
        assertThat(CompanyController.class.getDeclaredMethods())
                .anySatisfy(method -> assertThat(method.getAnnotation(GetMapping.class).value())
                        .containsExactly("/company"));
    }

    @Test
    void publicStatusShapesCannotExposeCredentialValues() {
        assertThat(componentNames(WeComConfigurationStatus.OAuthStatus.class))
                .containsExactly("enabled", "configured", "corpIdMasked",
                        "agentIdConfigured", "appSecretConfigured", "callbackConfigured")
                .doesNotContain("corpId", "agentId", "appSecret", "callbackUri", "token");
        assertThat(componentNames(WeComConfigurationStatus.DirectoryStatus.class))
                .containsExactly("enabled", "configured", "corpIdMasked",
                        "directorySecretConfigured", "profileSecretConfigured")
                .doesNotContain("corpId", "directorySecret", "profileSecret", "token");
    }

    @Test
    void memberProjectionCarriesStableConcurrencyAndRoleFacts() {
        assertThat(componentNames(IdentityMemberView.class))
                .contains("platformRoles", "authorizationVersion", "rowVersion", "etag")
                .doesNotContain("password", "secret", "token");
    }

    private static List<String> componentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}
