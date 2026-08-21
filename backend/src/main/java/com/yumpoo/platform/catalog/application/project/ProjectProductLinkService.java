package com.yumpoo.platform.catalog.application.project;

import com.yumpoo.platform.catalog.application.product.ProductRepository;
import com.yumpoo.platform.catalog.domain.product.Product;
import com.yumpoo.platform.catalog.domain.product.ProductStatus;
import com.yumpoo.platform.catalog.domain.project.Project;
import com.yumpoo.platform.catalog.domain.project.ProjectLifecycle;
import com.yumpoo.platform.catalog.domain.project.ProjectProductLink;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyCommand;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyScope;
import com.yumpoo.platform.foundation.application.idempotency.IdempotentCommandExecutor;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.yumpoo.platform.catalog.application.project.ProjectMembershipModels.Access;
import static com.yumpoo.platform.catalog.application.project.ProjectMembershipModels.ActorAccess;
import static com.yumpoo.platform.catalog.application.project.ProjectProductLinkCommands.*;
import static com.yumpoo.platform.catalog.application.project.ProjectProductLinkModels.*;

@Service
public class ProjectProductLinkService {

    private static final String LINKED_EVENT = "catalog.product_linked_to_project";
    private static final String UPDATED_EVENT = "catalog.project_product_link_updated";
    private static final String UNLINKED_EVENT = "catalog.product_unlinked_from_project";

    private final ProjectRepository projects;
    private final ProjectMembershipRepository memberships;
    private final ProductRepository products;
    private final ProjectProductLinkRepository links;
    private final IdempotentCommandExecutor idempotency;
    private final TransactionalEventPort events;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ProjectProductLinkService(
            ProjectRepository projects,
            ProjectMembershipRepository memberships,
            ProductRepository products,
            ProjectProductLinkRepository links,
            IdempotentCommandExecutor idempotency,
            TransactionalEventPort events,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.projects = projects;
        this.memberships = memberships;
        this.products = products;
        this.links = links;
        this.idempotency = idempotency;
        this.events = events;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Access requireVisible(CurrentActor actor, UUID projectId) {
        requireActor(actor);
        return memberships.findVisible(actor, projectId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public LinkList findActive(CurrentActor actor, UUID projectId) {
        requireVisible(actor, projectId);
        return new LinkList(links.findActiveViews(actor.companyId(), projectId).stream()
                .map(ProjectProductLinkService::view).toList());
    }

    @Transactional(readOnly = true)
    public ProductCandidatePage findCandidates(CurrentActor actor, UUID projectId, String query,
                                               OffsetPageRequest page) {
        Access access = requireVisible(actor, projectId);
        if (access.actorAccess() != ActorAccess.OWNER
                && access.actorAccess() != ActorAccess.COMPANY_ADMIN_READ_ONLY) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
        String normalized = query == null ? "" : query.strip();
        if (normalized.isEmpty() || normalized.length() > 80) {
            throw ApplicationException.validation(new FieldViolation(
                    "query", "INVALID_LENGTH", "产品候选关键字长度必须为 1 到 80 个字符"));
        }
        return links.findCandidates(actor.companyId(), projectId, normalized, page);
    }

    public IdempotencyExecutionResult create(Create command) {
        requireOwnerAccess(command.actor(), command.projectId());
        IdempotencyCommand idempotent = new IdempotencyCommand(
                new IdempotencyScope(command.actor().userId(), "POST", "createProjectProductLink",
                        command.idempotencyKey()), command.requestHash());
        return idempotency.execute(idempotent, () -> {
            Project project = lockOwnerProject(command.actor(), command.projectId());
            requireActiveProduct(project.companyId(), command.productId());
            if (command.primary() && links.findActivePrimary(project.companyId(), project.id()).isPresent()) {
                throw conflict("PRIMARY_PRODUCT_ALREADY_EXISTS");
            }
            Instant now = clock.instant();
            ProjectProductLink link = ProjectProductLink.create(UUID.randomUUID(), project.companyId(),
                    project.id(), command.productId(), command.relationType().toDomain(), command.primary(),
                    command.actor().userId(), now);
            if (!links.insert(link)) {
                throw conflict(command.primary()
                        ? "PRIMARY_PRODUCT_ALREADY_EXISTS" : "PRODUCT_RELATION_ALREADY_ACTIVE");
            }
            append(LINKED_EVENT, link, command.actor(), null);
            return stored(201, requiredView(link));
        });
    }

    @Transactional
    public LinkView changePrimary(ChangePrimary command) {
        Project project = lockOwnerProject(command.actor(), command.projectId());
        ProjectProductLink before = requiredLocked(project, command.linkId());
        requireVersion(before, command.expectedVersion());
        if (before.status() != com.yumpoo.platform.catalog.domain.project.ProjectProductLinkStatus.ACTIVE) {
            throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
        }
        if (before.primary() == command.primary()) return requiredView(before);
        if (command.primary()) {
            requireActiveProduct(project.companyId(), before.productId());
            if (links.findActivePrimary(project.companyId(), project.id()).isPresent()) {
                throw conflict("PRIMARY_PRODUCT_ALREADY_EXISTS");
            }
        }
        ProjectProductLink after;
        try {
            after = links.update(before.changePrimary(command.primary(), command.actor().userId(),
                            clock.instant()), command.expectedVersion())
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
        } catch (DataIntegrityViolationException exception) {
            throw conflict("PRIMARY_PRODUCT_ALREADY_EXISTS");
        }
        append(UPDATED_EVENT, after, command.actor(), before.primary());
        return requiredView(after);
    }

    public IdempotencyExecutionResult remove(Remove command) {
        requireOwnerAccess(command.actor(), command.projectId());
        IdempotencyCommand idempotent = new IdempotencyCommand(
                new IdempotencyScope(command.actor().userId(), "DELETE", "removeProjectProductLink",
                        command.idempotencyKey()), command.requestHash());
        return idempotency.execute(idempotent, () -> {
            Project project = lockOwnerProject(command.actor(), command.projectId());
            ProjectProductLink before = requiredLocked(project, command.linkId());
            requireVersion(before, command.expectedVersion());
            if (before.status() != com.yumpoo.platform.catalog.domain.project.ProjectProductLinkStatus.ACTIVE) {
                throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
            }
            ProjectProductLink after = links.update(before.remove(command.actor().userId(),
                            command.reason(), clock.instant()), command.expectedVersion())
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
            append(UNLINKED_EVENT, after, command.actor(), before.primary());
            return stored(200, requiredView(after));
        });
    }

    private void requireOwnerAccess(CurrentActor actor, UUID projectId) {
        Access access = requireVisible(actor, projectId);
        if (access.actorAccess() != ActorAccess.OWNER) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
    }

    private Project lockOwnerProject(CurrentActor actor, UUID projectId) {
        requireActor(actor);
        Project project = projects.lockById(actor.companyId(), projectId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        if (!project.ownerUserId().equals(actor.userId())) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
        if (project.lifecycle() == ProjectLifecycle.ARCHIVED) {
            throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
        }
        return project;
    }

    private Product requireActiveProduct(UUID companyId, UUID productId) {
        Product product = products.findById(companyId, productId).orElse(null);
        if (product == null || product.status() != ProductStatus.ACTIVE) {
            throw ApplicationException.validation(new FieldViolation(
                    "productId", "INVALID_PRODUCT", "产品必须是本企业的 ACTIVE Product"));
        }
        return product;
    }

    private ProjectProductLink requiredLocked(Project project, UUID linkId) {
        return links.lock(project.companyId(), project.id(), linkId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    private LinkView requiredView(ProjectProductLink link) {
        return links.findView(link.companyId(), link.projectId(), link.id())
                .map(ProjectProductLinkService::view)
                .orElseThrow(() -> new IllegalStateException("project product link view missing"));
    }

    private void append(String eventType, ProjectProductLink link, CurrentActor actor,
                        Boolean previousPrimary) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("linkId", link.id());
        payload.put("projectId", link.projectId());
        payload.put("productId", link.productId());
        payload.put("relationType", link.relationType().name());
        if (LINKED_EVENT.equals(eventType)) {
            payload.put("isPrimary", link.primary());
        } else if (UPDATED_EVENT.equals(eventType)) {
            payload.put("fromPrimary", previousPrimary);
            payload.put("toPrimary", link.primary());
        } else {
            payload.put("wasPrimary", previousPrimary);
        }
        events.append(new EventDraft(eventType, 1, "ProjectProductLink", link.id(),
                link.rowVersion(), link.companyId(), EventActor.user(actor.userId()),
                objectMapper.valueToTree(payload)));
    }

    private StoredCommandResult stored(int status, LinkView view) {
        try {
            return new StoredCommandResult(status, objectMapper.writeValueAsString(view), view.id(),
                    StrongEtag.format(view.rowVersion()));
        } catch (JacksonException exception) {
            throw new IllegalStateException("project product link response serialization failed", exception);
        }
    }

    private static LinkView view(LinkProjection projection) {
        ProjectProductLink link = projection.link();
        return new LinkView(link.id(), link.projectId(), link.productId(), projection.productCode(),
                projection.productName(), projection.productStatus(), link.relationType().name(),
                link.primary(), link.status().name(), link.linkedAt(), link.linkedByUserId(),
                link.updatedAt(), link.updatedByUserId(), link.removedAt(), link.removedByUserId(),
                link.removeReason(), link.rowVersion(), StrongEtag.format(link.rowVersion()));
    }

    private static void requireVersion(ProjectProductLink link, long expectedVersion) {
        if (link.rowVersion() != expectedVersion) {
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        }
    }

    private static ApplicationException conflict(String reason) {
        return ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION, reason);
    }

    private static void requireActor(CurrentActor actor) {
        if (actor == null) throw new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
    }
}
