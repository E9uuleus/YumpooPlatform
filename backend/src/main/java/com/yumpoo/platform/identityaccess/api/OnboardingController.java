package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.identityaccess.application.onboarding.OnboardingService;
import com.yumpoo.platform.identityaccess.application.onboarding.OnboardingState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

@ApiV1Controller
public class OnboardingController {
    private final OnboardingService service;
    private final CurrentActorProvider actors;

    public OnboardingController(OnboardingService service, CurrentActorProvider actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping("/me/onboarding")
    ResponseEntity<OnboardingState> state() {
        CurrentActor actor = actors.requiredActive();
        return response(service.state(actor.companyId(), actor.userId()));
    }

    @PutMapping("/me/onboarding/tours/{tourId}")
    ResponseEntity<OnboardingState> complete(
            @PathVariable @Pattern(regexp = "^[a-z][a-z0-9-]{1,47}$") String tourId,
            @Valid @RequestBody CompleteRequest body) {
        CurrentActor actor = actors.requiredActive();
        return response(service.complete(actor.companyId(), actor.userId(), tourId, body.version()));
    }

    public record CompleteRequest(@NotNull @Min(1) @Max(1000) Integer version) {
    }

    private static <T> ResponseEntity<T> response(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
