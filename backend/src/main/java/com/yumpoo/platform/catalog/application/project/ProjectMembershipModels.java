package com.yumpoo.platform.catalog.application.project;

import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

public final class ProjectMembershipModels {
    private ProjectMembershipModels() {}

    public enum ListStatus { ACTIVE, REMOVED, ALL }
    public enum ActorAccess { MEMBER, OWNER, COMPANY_ADMIN_READ_ONLY }
    public record Access(UUID projectId, UUID companyId, String lifecycle,
                         ActorAccess actorAccess, long projectVersion,
                         OptionalLong membershipVersion) {}
    public record Member(UUID membershipId, UUID projectId, UUID userId, String displayName,
                         String employmentStatus, String accountStatus, String membershipStatus,
                         boolean owner, Instant joinedAt, UUID joinedByUserId, Instant removedAt,
                         UUID removedByUserId, long rowVersion) {}
    public record MemberPage(List<Member> items, int page, int size, long totalElements, int totalPages) {}
    public record Candidate(UUID userId, String displayName, String employmentStatus,
                            String accountStatus, String membershipStatus, boolean owner,
                            Long membershipRowVersion) {}
    public record CandidatePage(List<Candidate> items, int page, int size,
                                long totalElements, int totalPages) {}
    public record MemberCommand(UUID companyId, UUID projectId, UUID userId,
                                Long expectedMembershipVersion, UUID actorUserId, String reason) {}
    public record OwnerCommand(UUID companyId, UUID projectId, long expectedProjectVersion,
                               UUID newOwnerUserId, UUID actorUserId) {}
    public record MemberResult(Member member, boolean created) {}
    public record OwnerResult(ProjectApplicationSnapshot before, ProjectApplicationSnapshot after,
                              Member ownerMembership, boolean membershipAdded) {}
}
