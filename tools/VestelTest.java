import io.github.icmrnbw.tvremote.protocol.VestelClient;

/** Sends a few keys to tools/fake_vestel_tv.py. See tools/README.md. */
public class VestelTest {
    public static void main(String[] args) throws Exception {
        System.out.println("phone: reachable=" + VestelClient.isReachable("127.0.0.1"));
        VestelClient tv = new VestelClient("127.0.0.1", reason -> System.out.println("phone: failed " + reason));
        tv.sendKey(VestelClient.POWER);
        tv.sendKey(VestelClient.VOLUME_UP);
        tv.sendKey(VestelClient.OK);
        tv.launchApp("YouTube");
        Thread.sleep(1500);
        tv.close();
        System.out.println("phone: done");
    }
}
