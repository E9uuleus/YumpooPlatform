package com.yumpoo.platform.identityaccess.application.directory;

public interface DirectorySyncAdministrationUseCase {

    DirectorySyncExecutionResult executeWithDisposition(DirectorySyncCommand command);
}
