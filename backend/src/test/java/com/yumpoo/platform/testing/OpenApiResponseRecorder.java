package com.yumpoo.platform.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

final class OpenApiResponseRecorder {

    private static final ConcurrentLinkedQueue<String> messages = new ConcurrentLinkedQueue<>();

    private OpenApiResponseRecorder() {}

    static void clear() {
        messages.clear();
    }

    static void record(String message) {
        messages.add(message);
    }

    static List<String> drain() {
        List<String> result = new ArrayList<>();
        for (String message; (message = messages.poll()) != null; ) {
            result.add(message);
        }
        return result;
    }
}
