package com.example.reports;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class StressThreadFactory implements ThreadFactory {

    private final AtomicInteger sequence = new AtomicInteger();

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(
                runnable,
                "jasper-stress-" + sequence.incrementAndGet()
        );
        thread.setDaemon(false);
        return thread;
    }
}
