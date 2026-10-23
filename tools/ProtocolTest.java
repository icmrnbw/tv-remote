import io.github.icmrnbw.tvremote.protocol.ClientIdentity;
import io.github.icmrnbw.tvremote.protocol.PairingSession;
import io.github.icmrnbw.tvremote.protocol.RemoteClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Drives the protocol classes against tools/fake_tv.py. See tools/README.md.
 *   java ProtocolTest gen WORK_DIR   -> writes the phone identity and client.pem
 *   java ProtocolTest run WORK_DIR   -> pairs and sends keys (fake_tv.py must be running)
 */
public class ProtocolTest {
    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[1]);
        if (args[0].equals("gen")) {
            ClientIdentity id = ClientIdentity.generate("atvremote");
            Files.write(work.resolve("client.key"), id.encodedKey());
            Files.write(work.resolve("client.der"), id.encodedCertificate());
            String pem = "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder().encodeToString(id.encodedCertificate())
                    + "\n-----END CERTIFICATE-----\n";
            Files.writeString(work.resolve("client.pem"), pem);
            System.out.println("phone: identity written");
            return;
        }
        ClientIdentity id = ClientIdentity.load(
                Files.readAllBytes(work.resolve("client.key")), Files.readAllBytes(work.resolve("client.der")));
        RemoteClient.Listener listener = new RemoteClient.Listener() {
            @Override public void onReady(String tvName) { System.out.println("phone: ready, TV is " + tvName); }
            @Override public void onPowerChanged(boolean on) { System.out.println("phone: power " + on); }
            @Override public void onVolumeChanged(int level, int max, boolean muted) {
                System.out.println("phone: volume " + level + "/" + max + " muted=" + muted);
            }
            @Override public void onDisconnected(String reason) { System.out.println("phone: disconnected " + reason); }
        };

        try {
            new RemoteClient("127.0.0.1", id, "Test phone", listener).connect();
            throw new AssertionError("expected NotPairedException");
        } catch (RemoteClient.NotPairedException expected) {
            System.out.println("phone: not paired yet, as expected");
        }

        PairingSession pairing = new PairingSession("127.0.0.1", id, "Test phone");
        pairing.start();
        Path codeFile = work.resolve("code.txt");
        for (int i = 0; i < 50 && !Files.exists(codeFile); i++) Thread.sleep(100);
        String code = Files.readString(codeFile).trim();
        String wrong = (code.charAt(0) == 'F' ? "0" : "F") + code.substring(1);
        try {
            pairing.finish(wrong);
            throw new AssertionError("expected WrongCodeException");
        } catch (PairingSession.WrongCodeException expected) {
            System.out.println("phone: wrong code caught locally: " + expected.getMessage());
        }
        pairing.finish(code.toLowerCase());
        pairing.close();
        System.out.println("phone: paired with code " + code);

        RemoteClient remote = new RemoteClient("127.0.0.1", id, "Test phone", listener);
        remote.connect();
        Thread.sleep(500);
        remote.sendKey(26);
        remote.sendKey(24);
        remote.sendKey(3, RemoteClient.DIRECTION_START_LONG);
        remote.launchApp("https://www.youtube.com");
        Thread.sleep(500);
        System.out.println("phone: connected=" + remote.isConnected());
        remote.close();
        System.out.println("phone: done");
    }
}
