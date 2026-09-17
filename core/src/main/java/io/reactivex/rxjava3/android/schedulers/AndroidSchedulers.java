package io.reactivex.rxjava3.android.schedulers;

import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.schedulers.Schedulers;

public final class AndroidSchedulers {
    private AndroidSchedulers() {
    }

    public static Scheduler mainThread() {
        return Schedulers.trampoline();
    }

    public static Scheduler from(java.util.concurrent.Executor executor) {
        return Schedulers.from(executor);
    }
}
