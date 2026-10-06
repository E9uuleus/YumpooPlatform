package com.yumpoo.platform.notification.api;

import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.notification.application.NotificationInboxQueryService;
import com.yumpoo.platform.notification.application.NotificationPreferenceService;
import com.yumpoo.platform.notification.application.NotificationModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.UUID;

@ApiV1Controller
public class NotificationController {
    private final CurrentActorProvider actors;
    private final NotificationInboxQueryService inbox;
    private final NotificationPreferenceService preferences;
    public NotificationController(CurrentActorProvider actors,NotificationInboxQueryService inbox,NotificationPreferenceService preferences) {
        this.actors=actors;this.inbox=inbox;this.preferences=preferences;
    }
    @GetMapping("/me/notifications")
    ResponseEntity<Page> list(@RequestParam(required=false) ListState state,@RequestParam(required=false) Group group,
            @RequestParam(required=false) String cursor,@RequestParam(required=false) Integer limit) {
        return response(inbox.list(actors.requiredActive(),state,group,cursor,limit));
    }
    @GetMapping("/me/notifications/unread-count")
    ResponseEntity<UnreadCounts> counts() { return response(inbox.counts(actors.requiredActive())); }
    @PostMapping("/me/notifications/{id}/read")
    ResponseEntity<UnreadCounts> read(@PathVariable UUID id) { return response(inbox.setState(actors.requiredActive(),id,State.READ)); }
    @PostMapping("/me/notifications/{id}/unread")
    ResponseEntity<UnreadCounts> unread(@PathVariable UUID id) { return response(inbox.setState(actors.requiredActive(),id,State.UNREAD)); }
    @PostMapping("/me/notifications/{id}/archive")
    ResponseEntity<UnreadCounts> archive(@PathVariable UUID id) { return response(inbox.setState(actors.requiredActive(),id,State.ARCHIVED)); }
    @PostMapping("/me/notifications/read-all")
    ResponseEntity<UnreadCounts> readAll(@Valid @RequestBody ReadAllRequest body) { return response(inbox.readAll(actors.requiredActive(),body.upTo(),body.group())); }
    @GetMapping("/me/projects/{projectId}/notification-preference")
    ResponseEntity<ProjectPreference> preference(@PathVariable UUID projectId) {
        return response(preferences.get(actors.requiredActive(),projectId));
    }
    @PutMapping("/me/projects/{projectId}/notification-preference")
    ResponseEntity<ProjectPreference> updatePreference(@PathVariable UUID projectId,@Valid @RequestBody PreferenceRequest body) {
        return response(preferences.update(actors.requiredActive(),projectId,new ProjectPreferenceUpdate(body.mode(),
                body.mention(),body.comment(),body.assigned(),body.connectionCreated())));
    }
    public record PreferenceRequest(@NotNull PreferenceMode mode,@NotNull Boolean mention,@NotNull Boolean comment,
            @NotNull Boolean assigned,@NotNull Boolean connectionCreated) {}
    public record ReadAllRequest(@NotNull Instant upTo,Group group) {}
    private static <T> ResponseEntity<T> response(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
