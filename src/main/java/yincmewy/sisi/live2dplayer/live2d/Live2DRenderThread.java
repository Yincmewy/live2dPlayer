package yincmewy.sisi.live2dplayer.live2d;

import java.util.concurrent.locks.LockSupport;

public final class Live2DRenderThread extends Thread {
    private final Live2DRuntime runtime;
    private volatile boolean running = true;

    public Live2DRenderThread(Live2DRuntime runtime) {
        super("Live2D Render Worker");
        this.runtime = runtime;
        setDaemon(true);
        setPriority(Thread.NORM_PRIORITY - 1);
    }

    @Override
    public void run() {
        while (running) {
            try {
                runtime.prepareFrame();
                LockSupport.parkNanos(runtime.workerFrameIntervalNanos());
                if (Thread.interrupted() && !running) {
                    break;
                }
            } catch (Throwable throwable) {
                LockSupport.parkNanos(runtime.workerFrameIntervalNanos());
            }
        }
    }

    public void stopWorker() {
        running = false;
        interrupt();
    }
}
