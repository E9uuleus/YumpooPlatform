package com.yumpoo.platform.identityaccess.application.directory;

public final class DirectorySyncLeaseLostException extends RuntimeException {

    public DirectorySyncLeaseLostException() {
        super("DIRECTORY_SYNC_LEASE_LOST");
    }
}
