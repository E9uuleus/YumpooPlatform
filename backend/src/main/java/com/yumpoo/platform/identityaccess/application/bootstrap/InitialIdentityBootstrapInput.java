package com.yumpoo.platform.identityaccess.application.bootstrap;

public record InitialIdentityBootstrapInput(
        String expectedCorpId,
        String appManagerWeComUserId,
        String companyAdminWeComUserId
) {
    public InitialIdentityBootstrapInput {
        expectedCorpId = requireText(expectedCorpId, "expectedCorpId");
        appManagerWeComUserId = requireText(appManagerWeComUserId, "appManagerWeComUserId");
        companyAdminWeComUserId = requireText(companyAdminWeComUserId, "companyAdminWeComUserId");
        if (appManagerWeComUserId.equals(companyAdminWeComUserId)) {
            throw new InitialIdentityBootstrapException(
                    "INPUT",
                    "INITIAL_IDENTITY_BOOTSTRAP_TARGETS_NOT_DISTINCT",
                    "Initial bootstrap role holders must be distinct"
            );
        }
    }

    @Override
    public String toString() {
        return "InitialIdentityBootstrapInput[identityData=REDACTED]";
    }

    private static String requireText(String value, String field) {
        if (value == null) {
            throw new InitialIdentityBootstrapException(
                    "INPUT",
                    "INITIAL_IDENTITY_BOOTSTRAP_INPUT_INVALID",
                    "Initial bootstrap input is invalid"
            );
        }
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > 256) {
            throw new InitialIdentityBootstrapException(
                    "INPUT",
                    "INITIAL_IDENTITY_BOOTSTRAP_INPUT_INVALID",
                    "Initial bootstrap input is invalid"
            );
        }
        return normalized;
    }
}
