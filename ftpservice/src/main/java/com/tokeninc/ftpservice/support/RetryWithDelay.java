package com.tokeninc.ftpservice.support;

import org.reactivestreams.Publisher;

import java.util.concurrent.TimeUnit;

import io.reactivex.Flowable;
import io.reactivex.functions.Function;

public class RetryWithDelay implements Function<Flowable<? extends Throwable>, Publisher<?>> {
    private static final int INFINITE = -1;

    private final long maxRetries;
    private final long retryDelayMillis;
    private int retryCount;

    public RetryWithDelay(final long maxRetries, final long retryDelayMillis) {
        this.maxRetries = maxRetries;
        this.retryDelayMillis = retryDelayMillis;
        this.retryCount = 0;
    }

    public RetryWithDelay(final long retryDelayMillis) {
        this(INFINITE, retryDelayMillis);
    }

    @Override
    public Publisher<?> apply(Flowable<? extends Throwable> flowable) throws Exception {
        return flowable.flatMap(new Function<Throwable, Publisher<?>>() {
            @Override
            public Publisher<?> apply(Throwable throwable) throws Exception {
                if (maxRetries == INFINITE || ++retryCount < maxRetries) {
                    // When this Observable calls onNext, the original
                    // Observable will be retried (i.e. re-subscribed).
                    return Flowable.timer(retryDelayMillis,
                            TimeUnit.MILLISECONDS);
                }

                // Max retries hit. Just pass the error along.
                return Flowable.error(throwable);
            }
        });
    }
}
