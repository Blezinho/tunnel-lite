package hev.htproxy;

/** Ligacao JNI com a biblioteca nativa hev-socks5-tunnel (tun2socks). */
public class TProxyService {
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    public static native void TProxyStartService(String configPath, int fd);

    public static native void TProxyStopService();

    public static native long[] TProxyGetStats();
}
