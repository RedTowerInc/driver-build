package hev.htproxy;
/** JNI contract of the pinned upstream hev-socks5-tunnel library. */
public final class TProxyService {
    static {System.loadLibrary("hev-socks5-tunnel");}
    public native boolean TProxyStartService(String path,int fd);
    public native boolean TProxyStopService();
    public native boolean TProxyIsRunning();
    public native long[] TProxyGetStats();
}
