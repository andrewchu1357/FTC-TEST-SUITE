package org.firstinspires.ftc.robotcore.internal.system;

import com.qualcomm.robotcore.util.ElapsedTime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

/** A timer that expires a set time after it's reset (on the simulated clock). */
public class Deadline extends ElapsedTime {
    protected long nsDuration;
    protected long nsDeadline;

    public Deadline(long duration, TimeUnit unit) {
        nsDuration = unit.toNanos(duration);
        reset();
    }

    @Override
    public void reset() {
        super.reset();
        nsDeadline = nsStartTime + nsDuration;
    }

    public void cancel() {
        nsDeadline = nsStartTime - 1;
    }

    public void expire() {
        nsDeadline = nsNow();
    }

    public long getDuration(TimeUnit unit) {
        return unit.convert(nsDuration, TimeUnit.NANOSECONDS);
    }

    public long getDeadline(TimeUnit unit) {
        return unit.convert(nsDeadline, TimeUnit.NANOSECONDS);
    }

    public long timeRemaining(TimeUnit unit) {
        return unit.convert(Math.max(0, nsDeadline - nsNow()), TimeUnit.NANOSECONDS);
    }

    public boolean hasExpired() {
        return nsNow() >= nsDeadline;
    }

    public boolean await(CountDownLatch latch) throws InterruptedException {
        return latch.await(timeRemaining(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS);
    }

    public boolean tryLock(Lock lock) throws InterruptedException {
        return lock.tryLock(timeRemaining(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS);
    }

    public boolean tryAcquire(Semaphore semaphore) throws InterruptedException {
        return semaphore.tryAcquire(timeRemaining(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS);
    }
}
