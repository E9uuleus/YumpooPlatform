package com.yumpoo.archfixture.worklog.api;

import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

@ApiV1Controller
public final class RequiredIfMatchController {

    @GetMapping("/fixture/default-required-if-match")
    void defaultRequiredIfMatch(@RequestHeader("If-Match") String ifMatch) {
    }

    @GetMapping("/fixture/explicit-required-if-match")
    void explicitRequiredIfMatch(
            @RequestHeader(name = "if-match", required = true) String ifMatch
    ) {
    }

    @GetMapping("/fixture/optional-if-match")
    void optionalIfMatch(
            @RequestHeader(value = "If-Match", required = false) String ifMatch
    ) {
    }
}
