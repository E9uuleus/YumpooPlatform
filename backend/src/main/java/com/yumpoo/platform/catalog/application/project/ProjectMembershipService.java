package com.yumpoo.platform.catalog.application.project;

import com.yumpoo.platform.catalog.application.project.ProjectMembershipModels.*;
import com.yumpoo.platform.catalog.domain.project.Project;
import com.yumpoo.platform.catalog.domain.project.ProjectLifecycle;
import com.yumpoo.platform.catalog.domain.project.ProjectMembership;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.MinimalUserPage;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshot;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshotQuery;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

@Service
public class ProjectMembershipService {
    private final ProjectRepository projectRepository;
    private final ProjectMembershipRepository membershipRepository;
    private final MinimalUserSnapshotQuery users;
    private final Clock clock;

    public ProjectMembershipService(ProjectRepository projectRepository,
                                    ProjectMembershipRepository membershipRepository,
                                    MinimalUserSnapshotQuery users, Clock clock) {
        this.projectRepository = projectRepository;
        this.membershipRepository = membershipRepository;
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Access requireVisible(CurrentActor actor, UUID projectId) {
        return membershipRepository.findVisible(actor, projectId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public java.util.List<ProjectApplicationSnapshot> findGovernedByOwner(UUID companyId, UUID ownerUserId) {
        return projectRepository.findGovernedByOwner(companyId, ownerUserId).stream()
                .map(ProjectMembershipService::project).toList();
    }

    @Transactional(readOnly = true)
    public java.util.Optional<ProjectApplicationSnapshot> findProject(UUID companyId, UUID projectId) {
        return projectRepository.findById(companyId, projectId).map(ProjectMembershipService::project);
    }

    @Transactional(readOnly = true)
    public boolean isActiveMember(UUID companyId, UUID projectId, UUID userId) {
        return membershipRepository.existsActive(companyId, projectId, userId);
    }

    @Transactional(readOnly = true)
    public Set<UUID> findActiveMemberIds(UUID companyId, UUID projectId,
            Collection<UUID> userIds) {
        return membershipRepository.findByUsers(companyId, projectId, userIds).values().stream()
                .filter(membership -> membership.status()
                        == com.yumpoo.platform.catalog.domain.project.ProjectMembershipStatus.ACTIVE)
                .map(com.yumpoo.platform.catalog.domain.project.ProjectMembership::userId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Transactional(readOnly = true)
    public MemberPage findMembers(CurrentActor actor, UUID projectId, ListStatus status,
            OffsetPageRequest page) {
        return findMembers(actor, projectId, status, null, page);
    }

    @Transactional(readOnly = true)
    public MemberPage findMembers(CurrentActor actor, UUID projectId, ListStatus status,
            String query, OffsetPageRequest page) {
        Access access = requireVisible(actor, projectId);
        List<ProjectMembership> memberships = membershipRepository.findPage(
                actor.companyId(), projectId, status, query, page);
        Map<UUID, MinimalUserSnapshot> identities = users.findByUserIds(actor.companyId(),
                memberships.stream().map(ProjectMembership::userId).toList());
        Project project = requiredProject(actor.companyId(), projectId);
        List<Member> items = memberships.stream()
                .map(m -> snapshot(m, project.ownerUserId(), requiredIdentity(identities, m.userId())))
                .toList();
        long total = membershipRepository.count(actor.companyId(), projectId, status, query);
        return new MemberPage(items, page.page(), page.size(), total, pages(total, page.size()));
    }

    @Transactional(readOnly = true)
    public CandidatePage findCandidates(CurrentActor actor, UUID projectId,
                                                     String name, OffsetPageRequest page) {
        Access access = requireVisible(actor, projectId);
        if (access.actorAccess() != ActorAccess.OWNER
                && !actor.hasRole(PlatformRoleCode.COMPANY_ADMIN)) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
        MinimalUserPage userPage = users.findActiveEnabledByName(actor.companyId(), name, page);
        Map<UUID, ProjectMembership> memberships = membershipRepository.findByUsers(
                actor.companyId(), projectId, userPage.items().stream().map(MinimalUserSnapshot::userId).toList());
        Project project = requiredProject(actor.companyId(), projectId);
        List<Candidate> items = userPage.items().stream().map(user -> {
            ProjectMembership membership = memberships.get(user.userId());
            return new Candidate(user.userId(), user.displayName(),
                    user.employmentStatus(), user.accountStatus(),
                    membership == null ? null : membership.status().name(),
                    project.ownerUserId().equals(user.userId()),
                    membership == null ? null : membership.rowVersion());
        }).toList();
        return new CandidatePage(items, page.page(), page.size(), userPage.totalElements(),
                pages(userPage.totalElements(), page.size()));
    }

    @Transactional
    public MemberResult add(MemberCommand mutation) {
        Project project = lockWritableProject(mutation.companyId(), mutation.projectId());
        ProjectMembership existing = membershipRepository.lock(mutation.companyId(), mutation.projectId(),
                mutation.userId()).orElse(null);
        Instant now = clock.instant();
        ProjectMembership changed;
        boolean created;
        if (existing == null) {
            if (mutation.expectedMembershipVersion() != null) {
                throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
            }
            changed = ProjectMembership.activeMember(UUID.randomUUID(), mutation.companyId(),
                    mutation.projectId(), mutation.userId(), mutation.actorUserId(), now);
            if (!membershipRepository.insert(changed)) {
                throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
            }
            created = true;
        } else {
            if (existing.status() == com.yumpoo.platform.catalog.domain.project.ProjectMembershipStatus.ACTIVE) {
                throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
            }
            if (mutation.expectedMembershipVersion() == null) {
                throw new ApplicationException(StandardErrorCode.PRECONDITION_REQUIRED);
            }
            requireVersion(existing, mutation.expectedMembershipVersion());
            changed = membershipRepository.update(existing.reactivate(mutation.actorUserId(), now),
                            mutation.expectedMembershipVersion())
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
            created = false;
        }
        return new MemberResult(snapshot(changed, project.ownerUserId(),
                requiredIdentity(mutation.companyId(), mutation.userId())), created);
    }

    @Transactional
    public MemberResult remove(MemberCommand mutation) {
        Project project = lockWritableProject(mutation.companyId(), mutation.projectId());
        if (project.ownerUserId().equals(mutation.userId())) {
            throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
        }
        ProjectMembership existing = membershipRepository.lock(mutation.companyId(), mutation.projectId(),
                        mutation.userId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        if (mutation.expectedMembershipVersion() == null) {
            throw new ApplicationException(StandardErrorCode.PRECONDITION_REQUIRED);
        }
        requireVersion(existing, mutation.expectedMembershipVersion());
        if (existing.status() != com.yumpoo.platform.catalog.domain.project.ProjectMembershipStatus.ACTIVE) {
            throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
        }
        ProjectMembership changed = membershipRepository.update(
                        existing.remove(mutation.actorUserId(), mutation.reason(), clock.instant()),
                        mutation.expectedMembershipVersion())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
        return new MemberResult(snapshot(changed, project.ownerUserId(),
                requiredIdentity(mutation.companyId(), mutation.userId())), false);
    }

    @Transactional
    public OwnerResult reassignOwner(OwnerCommand mutation) {
        Project before = lockWritableProject(mutation.companyId(), mutation.projectId());
        if (before.rowVersion() != mutation.expectedProjectVersion()) {
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        }
        if (before.ownerUserId().equals(mutation.newOwnerUserId())) {
            throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
        }
        ProjectMembership membership = membershipRepository.lock(mutation.companyId(), mutation.projectId(),
                mutation.newOwnerUserId()).orElse(null);
        boolean added = false;
        if (membership == null) {
            membership = ProjectMembership.activeMember(UUID.randomUUID(), mutation.companyId(),
                    mutation.projectId(), mutation.newOwnerUserId(), mutation.actorUserId(), clock.instant());
            if (!membershipRepository.insert(membership)) {
                throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
            }
            added = true;
        } else if (membership.status()
                == com.yumpoo.platform.catalog.domain.project.ProjectMembershipStatus.REMOVED) {
            long expected = membership.rowVersion();
            membership = membershipRepository.update(membership.reactivate(mutation.actorUserId(), clock.instant()),
                            expected).orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
            added = true;
        }
        Project after = projectRepository.reassignOwner(
                        before.reassignOwner(mutation.newOwnerUserId(), mutation.actorUserId(), clock.instant()),
                        mutation.expectedProjectVersion())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
        return new OwnerResult(project(before), project(after),
                snapshot(membership, after.ownerUserId(),
                        requiredIdentity(mutation.companyId(), mutation.newOwnerUserId())), added);
    }

    private Project lockWritableProject(UUID companyId, UUID projectId) {
        Project project = projectRepository.lockById(companyId, projectId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        if (project.lifecycle() == ProjectLifecycle.ARCHIVED) {
            throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
        }
        return project;
    }

    private Project requiredProject(UUID companyId, UUID projectId) {
        return projectRepository.findById(companyId, projectId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    private MinimalUserSnapshot requiredIdentity(UUID companyId, UUID userId) {
        return users.findByUserId(companyId, userId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    private static MinimalUserSnapshot requiredIdentity(Map<UUID, MinimalUserSnapshot> users, UUID userId) {
        MinimalUserSnapshot user = users.get(userId);
        if (user == null) throw new IllegalStateException("membership identity snapshot missing");
        return user;
    }

    private static Member snapshot(ProjectMembership membership, UUID ownerUserId,
                                                  MinimalUserSnapshot user) {
        return new Member(membership.id(), membership.projectId(), membership.userId(),
                user.displayName(), user.employmentStatus(), user.accountStatus(), membership.status().name(),
                ownerUserId.equals(membership.userId()), membership.joinedAt(), membership.joinedByUserId(),
                membership.removedAt(), membership.removedByUserId(), membership.rowVersion());
    }

    private static ProjectApplicationSnapshot project(Project project) {
        return new ProjectApplicationSnapshot(project.id(), project.companyId(), project.workspaceId(), project.code(),
                project.name(), project.description(), project.projectType().name(), project.lifecycle().name(),
                project.ownerUserId(), project.templateKey(), project.templateVersion(), project.customerName(),
                project.customerReference(), project.deliverySite(), project.contactNote(), project.rowVersion());
    }

    private static int pages(long total, int size) { return (int) ((total + size - 1) / size); }

    private static void requireVersion(ProjectMembership membership, long expected) {
        if (membership.rowVersion() != expected) {
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        }
    }
}
