package dev.geohide.switcher;
import java.io.Closeable;
import java.util.concurrent.*;
final class IoCleanup {
    // TLS close_notify and HTTP/2 cancellation can perform network I/O on Android.
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Connection-cleanup");t.setDaemon(true);return t;});
    static void run(Runnable task){IO.execute(task);}
    static void close(Closeable c){try{c.close();}catch(Exception ignored){}}
}
