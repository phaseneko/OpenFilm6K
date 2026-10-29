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

    public static void submit(final String tag, final Job job) {
        pending.incrementAndGet();
        EX.execute(new Runnable() { public void run() {
            try {
                job.run();
            } catch (Throwable t) {
                Engine.dbg("queue/" + tag + ": " + t);
                MainActivity.say("queue " + tag + ": " + t);
            } finally {
                pending.decrementAndGet();
            }
        }});
    }

    /** jobs waiting or running right now (for status text) */
    public static int pending() { return pending.get(); }
}
