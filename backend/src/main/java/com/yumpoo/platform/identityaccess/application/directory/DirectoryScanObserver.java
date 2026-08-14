package com.yumpoo.platform.identityaccess.application.directory;

import java.util.List;

@FunctionalInterface
public interface DirectoryScanObserver {

    void pageCollected(int pass, int pageNumber, String nextCursor, List<String> externalUserIds);
}
