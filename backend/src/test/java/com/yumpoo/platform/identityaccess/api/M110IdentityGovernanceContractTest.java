package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.identityaccess.application.account.AccountStatusChangeCommand;
import com.yumpoo.platform.identityaccess.application.authorization.RevokePlatformRoleCommand;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class M110IdentityGovernanceContractTest {

    @Test
    void writeBodiesContainOnlyServerApprovedBusinessFields() {
        assertThat(componentNames(RoleGrantRequest.class)).containsExactly("userId", "reason");
        assertThat(componentNames(GovernanceReasonRequest.class)).containsExactly("reason");
        assertThat(componentNames(RoleGrantRequest.class)).doesNotContain(
                "actor", "actorUserId", "role", "scope", "expectedVersion", "rowVersion");
        assertThat(componentNames(GovernanceReasonRequest.class)).doesNotContain(
                "actor", "actorUserId", "role", "scope", "expectedVersion", "rowVersion");
    }

    @Test
    void applicationCommandsCarryTrustedActorAndRolePathBinding() {
        assertThat(componentNames(RevokePlatformRoleCommand.class))
                .contains("expectedRole", "expectedAssignmentRowVersion", "actor");
        assertThat(componentNames(AccountStatusChangeCommand.class))
                .contains("actor", "expectedRowVersion")
                .doesNotContain("actorUserId");
    }

    @Test
    void controllerPublishesOnlyApprovedGovernancePaths() {
        List<String> getPaths = Arrays.stream(IdentityGovernanceController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class))
                .flatMap(method -> Arrays.stream(method.getAnnotation(GetMapping.class).value()))
                .sorted().toList();
        List<String> postPaths = Arrays.stream(IdentityGovernanceController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class))
                .flatMap(method -> Arrays.stream(method.getAnnotation(PostMapping.class).value()))
                .sorted().toList();
        List<String> deletePaths = Arrays.stream(IdentityGovernanceController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(DeleteMapping.class))
                .flatMap(method -> Arrays.stream(method.getAnnotation(DeleteMapping.class).value()))
                .sorted().toList();

        assertThat(getPaths).containsExactly(
                "/admin/members/{userId}/governance-state",
                "/admin/role-assignments");
        assertThat(postPaths).containsExactly(
                "/admin/app-manager-assignments",
                "/admin/company-admin-assignments",
                "/admin/members/{userId}/account-disable",
                "/admin/members/{userId}/account-enable");
        assertThat(deletePaths).containsExactly(
                "/admin/app-manager-assignments/{assignmentId}",
                "/admin/company-admin-assignments/{assignmentId}");
        assertThat(getPaths).noneMatch(path -> path.contains("security-audit"));
    }

    private static List<String> componentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}
