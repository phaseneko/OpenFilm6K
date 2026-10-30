package com.openfilm6k.host;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Single background queue for every photo that needs denoise + grading.
 *  One worker thread: the ncnn net and the GL engine are both serial anyway, so
 *  running callers in parallel only piles up decoded bitmaps in RAM.
 *  Intake paths (camera / spy / ftp / rewind / luma) hand a job over and return
 *  immediately, so the camera is never kept waiting and no Wi-Fi link is held
 *  while a photo is being rendered. */
public class RenderQueue {
    private static final ExecutorService EX = Executors.newSingleThreadExecutor();
    private static final AtomicInteger pending = new AtomicInteger();

    public interface Job { void run() throws Throwable; }

    /** busy/idle transitions: busy=true when the first job of a batch starts, false when the queue drains */
    public interface Listener { void onBusyChanged(boolean busy, int okPhotos); }
    private static volatile Listener listener;
    public static void setListener(Listener l) { listener = l; }

    private static final AtomicInteger okPhotos = new AtomicInteger();

    /** a photo finished the whole pipeline successfully (denoise + grade + album write) */
    public static void noteOk() { okPhotos.incrementAndGet(); }

    private static void fire(boolean busy, int ok) {
        Listener l = listener;
        if (l != null) try { l.onBusyChanged(busy, ok); } catch (Throwable ig) {}
    }

    public static void submit(final String tag, final Job job) {
        if (pending.getAndIncrement() == 0) { okPhotos.set(0); fire(true, 0); }
        EX.execute(new Runnable() { public void run() {
            try {
                job.run();
            } catch (Throwable t) {
                Engine.dbg("queue/" + tag + ": " + t);
                MainActivity.say("queue " + tag + ": " + t);
            } finally {
                if (pending.decrementAndGet() == 0) fire(false, okPhotos.getAndSet(0));
            }
        }});
    }

    /** jobs waiting or running right now (for status text) */
    public static int pending() { return pending.get(); }
}
