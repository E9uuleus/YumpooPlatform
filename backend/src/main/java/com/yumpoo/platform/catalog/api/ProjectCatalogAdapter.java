package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.catalog.application.project.ProjectApplicationSnapshot;
import com.yumpoo.platform.catalog.application.project.ProjectCreateCommand;
import com.yumpoo.platform.catalog.application.project.ProjectCreationService;
import com.yumpoo.platform.catalog.application.project.ProjectMembershipModels;
import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import org.springframework.stereotype.Component;

@Component
public class ProjectCatalogAdapter implements ProjectLifecycleCommandPort, ProjectMembershipQuery,
        ProjectMembershipCommandPort, ProjectAccessSnapshotQuery, ProjectOwnerScopeQuery {

    private final ProjectCreationService service;
    private final com.yumpoo.platform.catalog.application.project.ProjectMembershipService membershipService;
    private final com.yumpoo.platform.catalog.application.project.ProjectLifecycleService lifecycleService;

    public ProjectCatalogAdapter(ProjectCreationService service,
            com.yumpoo.platform.catalog.application.project.ProjectMembershipService membershipService,
            com.yumpoo.platform.catalog.application.project.ProjectLifecycleService lifecycleService) {
        this.service = service;
        this.membershipService = membershipService;
        this.lifecycleService = lifecycleService;
    }

    @Override
    public java.util.Optional<ProjectAccessSnapshot> findVisible(
            com.yumpoo.platform.identityaccess.api.CurrentActor actor, java.util.UUID projectId) {
        try { return java.util.Optional.of(access(membershipService.requireVisible(actor, projectId))); }
        catch (com.yumpoo.platform.foundation.application.error.ApplicationException exception) {
            if (exception.errorCode() == com.yumpoo.platform.foundation.application.error.StandardErrorCode.RESOURCE_NOT_FOUND)
                return java.util.Optional.empty();
            throw exception;
        }
    }

    @Override
    public ProjectAccessSnapshot requireVisible(com.yumpoo.platform.identityaccess.api.CurrentActor actor,
                                                java.util.UUID projectId) {
        return access(membershipService.requireVisible(actor, projectId));
    }

    @Override
    public ProjectMemberPage findMembers(com.yumpoo.platform.identityaccess.api.CurrentActor actor,
            java.util.UUID projectId, ProjectMembershipStatus status,
            com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest page) {
        var result=membershipService.findMembers(actor,projectId,
                ProjectMembershipModels.ListStatus.valueOf(status.name()),page);
        return new ProjectMemberPage(result.items().stream().map(ProjectCatalogAdapter::member).toList(),
                result.page(),result.size(),result.totalElements(),result.totalPages());
    }

    @Override
    public ProjectMemberCandidatePage findCandidates(com.yumpoo.platform.identityaccess.api.CurrentActor actor,
            java.util.UUID projectId, String name,
            com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest page) {
        var result=membershipService.findCandidates(actor,projectId,name,page);
        return new ProjectMemberCandidatePage(result.items().stream().map(candidate ->
                new ProjectMemberCandidate(candidate.userId(),candidate.displayName(),
                        candidate.employmentStatus(),candidate.accountStatus(),candidate.membershipStatus(),
                        candidate.owner(),candidate.membershipRowVersion(),candidate.membershipRowVersion()==null
                                ? null:StrongEtag.format(candidate.membershipRowVersion()))).toList(),
                result.page(),result.size(),result.totalElements(),result.totalPages());
    }

    @Override public ProjectMemberMutationResult add(ProjectMemberMutation mutation) {
        var result=membershipService.add(command(mutation));
        return new ProjectMemberMutationResult(member(result.member()),result.created());
    }

    @Override public ProjectMemberMutationResult remove(ProjectMemberMutation mutation) {
        var result=membershipService.remove(command(mutation));
        return new ProjectMemberMutationResult(member(result.member()),result.created());
    }

    @Override public ProjectOwnerReassignmentResult reassignOwner(ProjectOwnerReassignmentMutation mutation) {
        var result=membershipService.reassignOwner(new ProjectMembershipModels.OwnerCommand(
                mutation.companyId(),mutation.projectId(),mutation.expectedProjectVersion(),
                mutation.newOwnerUserId(),mutation.actorUserId()));
        return new ProjectOwnerReassignmentResult(snapshot(result.before()),snapshot(result.after()),
                member(result.ownerMembership()),result.membershipAdded());
    }

    @Override
    public java.util.List<ProjectSnapshot> findGovernedByOwner(java.util.UUID companyId,
                                                               java.util.UUID ownerUserId) {
        return membershipService.findGovernedByOwner(companyId, ownerUserId).stream()
                .map(ProjectCatalogAdapter::snapshot).toList();
    }

    @Override
    public java.util.Optional<ProjectSnapshot> find(java.util.UUID companyId, java.util.UUID projectId) {
        return membershipService.findProject(companyId, projectId).map(ProjectCatalogAdapter::snapshot);
    }

    @Override
    public ProjectSnapshot create(ProjectCreationMutation mutation) {
        return snapshot(service.create(new ProjectCreateCommand(
                mutation.companyId(), mutation.workspaceId(), mutation.code(), mutation.name(),
                mutation.description(), mutation.projectType(), mutation.ownerUserId(),
                mutation.templateKey(), mutation.templateVersion(), mutation.customerName(),
                mutation.customerReference(), mutation.deliverySite(), mutation.contactNote(),
                mutation.actorUserId())));
    }

    @Override
    public ProjectActivationSnapshot lockForActivation(ProjectActivationMutation mutation) {
        var state = lifecycleService.lockForActivation(activationCommand(mutation));
        return new ProjectActivationSnapshot(snapshot(state.project()), state.ownerMembershipActive());
    }

    @Override
    public ProjectSnapshot activate(ProjectActivationMutation mutation) {
        return snapshot(lifecycleService.activate(activationCommand(mutation)));
    }

    private static com.yumpoo.platform.catalog.application.project.ProjectActivationCommand activationCommand(
            ProjectActivationMutation mutation) {
        return new com.yumpoo.platform.catalog.application.project.ProjectActivationCommand(
                mutation.companyId(), mutation.projectId(), mutation.expectedRowVersion(),
                mutation.actorUserId());
    }

    private static ProjectSnapshot snapshot(ProjectApplicationSnapshot project) {
        return new ProjectSnapshot(project.projectId(), project.companyId(), project.workspaceId(),
                project.code(), project.name(), project.description(), project.projectType(),
                project.lifecycle(), project.ownerUserId(), project.templateKey(),
                project.templateVersion(), project.customerName(), project.customerReference(),
                project.deliverySite(), project.contactNote(), project.rowVersion());
    }

    private static ProjectMembershipModels.MemberCommand command(ProjectMemberMutation mutation) {
        return new ProjectMembershipModels.MemberCommand(mutation.companyId(),mutation.projectId(),
                mutation.userId(),mutation.expectedMembershipVersion(),mutation.actorUserId(),mutation.reason());
    }

    private static ProjectMemberSnapshot member(ProjectMembershipModels.Member member) {
        return ProjectMemberSnapshot.of(member.membershipId(),member.projectId(),member.userId(),
                member.displayName(),member.employmentStatus(),member.accountStatus(),member.membershipStatus(),
                member.owner(),member.joinedAt(),member.joinedByUserId(),member.removedAt(),
                member.removedByUserId(),member.rowVersion());
    }

    private static ProjectAccessSnapshot access(ProjectMembershipModels.Access access) {
        return new ProjectAccessSnapshot(access.projectId(),access.companyId(),
                ProjectAccessSnapshot.ProjectLifecycle.valueOf(access.lifecycle()),
                ProjectAccessSnapshot.ActorProjectAccess.valueOf(access.actorAccess().name()),
                access.projectVersion(),access.membershipVersion());
    }
}
