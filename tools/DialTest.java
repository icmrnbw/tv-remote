import io.github.icmrnbw.tvremote.protocol.DlnaClient;
import io.github.icmrnbw.tvremote.protocol.VestelClient;

/** Drives tools/fake_dial_tv.py: DIAL apps plus DLNA volume. See tools/README.md. */
public class DialTest {
    public static void main(String[] args) throws Exception {
        System.out.println("phone: smartCenter=" + VestelClient.hasSmartCenter("127.0.0.1"));
        VestelClient dial = new VestelClient("127.0.0.1", r -> System.out.println("phone: dial failed " + r));
        DlnaClient dlna = DlnaClient.find("127.0.0.1", null, new DlnaClient.Listener() {
            @Override public void onVolume(int volume, boolean muted) {
                System.out.println("phone: volume " + volume + " muted=" + muted);
            }
            @Override public void onSendFailed(String reason) { System.out.println("phone: dlna failed " + reason); }
        });
        System.out.println("phone: dlna found=" + (dlna != null));
        dial.launchApp("YouTube");
        dlna.volumeUp();
        dlna.volumeUp();
        dlna.volumeDown();
        dlna.toggleMute();
        dlna.toggleMute();
        dial.stopApps();
        Thread.sleep(2000);
        dial.close();
        dlna.close();
    }
}
