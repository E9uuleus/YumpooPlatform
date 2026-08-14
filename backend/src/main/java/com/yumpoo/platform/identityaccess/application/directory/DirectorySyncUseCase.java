package com.yumpoo.platform.identityaccess.application.directory;

public interface DirectorySyncUseCase {

    DirectorySyncRunSnapshot execute(DirectorySyncCommand command);
}
