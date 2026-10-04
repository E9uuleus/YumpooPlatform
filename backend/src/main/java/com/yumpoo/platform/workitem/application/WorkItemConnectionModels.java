package com.yumpoo.platform.workitem.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class WorkItemConnectionModels {
    private WorkItemConnectionModels() {}

    public record ConnectionCardLabel(String code, String name, String colorToken) {}
    public record ConnectionCardStatus(String code, String name, String colorToken, String category) {}
    public record ConnectionCardCategory(UUID id, String name, String colorToken) {}
    public record ConnectionCardAssignee(UUID userId, String displayName) {}
    public record ConnectionCard(UUID workItemId, String itemNo, String title, boolean archived,
            UUID projectId, String projectCode, String projectName, String projectLifecycle,
            ConnectionCardStatus status, ConnectionCardLabel priority, ConnectionCardCategory category,
            ConnectionCardAssignee assignee, boolean canOpen) {}
    public record Capabilities(boolean canUnlink) {}
    public record ConnectionView(UUID id, String etag, long rowVersion, UUID columnId, String columnName,
            String origin, boolean active, ConnectionCard source, ConnectionCard target, Instant createdAt,
            ConnectionCardAssignee createdBy, Capabilities capabilities) {}
    public record Outgoing(UUID columnId, List<ConnectionView> connections) {}
    public record IncomingByColumn(UUID columnId, List<ConnectionView> connections, long total) {}
    public record Cell(UUID workItemId, List<Outgoing> outgoing, List<ConnectionView> incoming, long incomingTotal,
            List<IncomingByColumn> incomingByColumn) {}
    public record CellList(List<Cell> items) {}
    public record ConnectionPage(List<ConnectionView> items, int page, int size, long totalElements, int totalPages) {}
    public record CreateOptions(UUID targetProjectId, String targetProjectName,
            List<ConnectionCardCategory> categories, UUID defaultContentId) {}
    public record Parent(UUID workItemId, String title) {}
    public record Candidate(ConnectionCard card, Parent parent, boolean alreadyConnected) {}
    public record CandidatePage(List<Candidate> items, int page, int size, long totalElements, int totalPages) {}
    public enum CandidateField { NAME, ASSIGNEE, STATUS, PRIORITY, CONTENT }
    public enum CandidateSort { RECENT, TITLE }
}
