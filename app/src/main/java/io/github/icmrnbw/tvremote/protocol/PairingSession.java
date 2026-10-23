package io.github.icmrnbw.tvremote.protocol;

import java.io.Closeable;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.util.Locale;

import javax.net.ssl.SSLSocket;

/**
 * Pairs the phone with the TV (port 6467). {@link #start()} makes the TV show a 6 character
 * code; {@link #finish(String)} sends the proof that we saw it.
 */
public final class PairingSession implements Closeable {
    public static final int PORT = 6467;

    private static final int STATUS_OK = 200;
    private static final int STATUS_BAD_SECRET = 402;
    private static final int ENCODING_HEXADECIMAL = 3;
    private static final int ROLE_INPUT = 1;
    private static final int CODE_LENGTH = 6;

    // Outer message fields
    private static final int PAIRING_REQUEST = 10;
    private static final int PAIRING_REQUEST_ACK = 11;
    private static final int OPTIONS = 20;
    private static final int CONFIGURATION = 30;
    private static final int CONFIGURATION_ACK = 31;
    private static final int SECRET = 40;
    private static final int SECRET_ACK = 41;

    /** The code typed does not match the one on the TV; the session is still usable. */
    public static final class WrongCodeException extends IOException {
        WrongCodeException(String message) { super(message); }
    }

    private final String host;
    private final ClientIdentity identity;
    private final String clientName;
    private SSLSocket socket;

    public PairingSession(String host, ClientIdentity identity, String clientName) {
        this.host = host;
        this.identity = identity;
        this.clientName = clientName;
    }

    public void start() throws IOException {
        socket = identity.connect(host, PORT, 10_000);
        // The user needs time to read the code off the screen.
        socket.setSoTimeout(0);

        send(new Proto.Writer().message(PAIRING_REQUEST, new Proto.Writer()
                .string(1, "atvremote")
                .string(2, clientName)));
        expect(PAIRING_REQUEST_ACK);

        send(new Proto.Writer().message(OPTIONS, new Proto.Writer()
                .message(1, hexEncoding())
                .uint(3, ROLE_INPUT)));
        expect(OPTIONS);

        send(new Proto.Writer().message(CONFIGURATION, new Proto.Writer()
                .message(1, hexEncoding())
                .uint(2, ROLE_INPUT)));
        expect(CONFIGURATION_ACK);
    }

    public void finish(String code) throws IOException {
        String clean = code.replaceAll("\\s", "").toUpperCase(Locale.US);
        if (!clean.matches("[0-9A-F]{" + CODE_LENGTH + "}")) {
            throw new WrongCodeException("The code is 6 characters, using 0-9 and A-F");
        }
        byte[] codeBytes = new BigInteger("1" + clean, 16).toByteArray(); // leading 1 keeps zeros
        byte[] secret;
        try {
            RSAPublicKey server = (RSAPublicKey) socket.getSession().getPeerCertificates()[0].getPublicKey();
            RSAPublicKey client = (RSAPublicKey) identity.certificate.getPublicKey();
            secret = computeSecret(client, server, codeBytes[2], codeBytes[3]);
        } catch (GeneralSecurityException | ClassCastException e) {
            throw new IOException("Couldn't read the TV's certificate", e);
        }
        if (secret[0] != codeBytes[1]) {
            throw new WrongCodeException("That code doesn't match the one on the TV");
        }
        send(new Proto.Writer().message(SECRET, new Proto.Writer().bytes(1, secret)));
        expect(SECRET_ACK);
    }

    static byte[] computeSecret(RSAPublicKey client, RSAPublicKey server, byte b1, byte b2)
            throws GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(unsigned(client.getModulus()));
        digest.update(unsigned(client.getPublicExponent()));
        digest.update(unsigned(server.getModulus()));
        digest.update(unsigned(server.getPublicExponent()));
        digest.update(new byte[] {b1, b2});
        return digest.digest();
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] b = value.toByteArray();
        if (b.length > 1 && b[0] == 0) {
            byte[] trimmed = new byte[b.length - 1];
            System.arraycopy(b, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return b;
    }

    private static Proto.Writer hexEncoding() {
        return new Proto.Writer().uint(1, ENCODING_HEXADECIMAL).uint(2, CODE_LENGTH);
    }

    private void send(Proto.Writer body) throws IOException {
        Proto.Writer outer = new Proto.Writer().uint(1, 2).uint(2, STATUS_OK);
        byte[] b = body.toByteArray();
        byte[] all = outer.toByteArray();
        byte[] message = new byte[all.length + b.length];
        System.arraycopy(all, 0, message, 0, all.length);
        System.arraycopy(b, 0, message, all.length, b.length);
        Proto.writeFramed(socket.getOutputStream(), message);
    }

    private void expect(int field) throws IOException {
        Proto.Message reply = Proto.Message.parse(Proto.readFramed(socket.getInputStream()));
        long status = reply.uint(2, 0);
        if (status == STATUS_BAD_SECRET) throw new WrongCodeException("The TV rejected the code");
        if (status != STATUS_OK) throw new IOException("The TV refused pairing (status " + status + ")");
        if (!reply.has(field)) throw new IOException("Unexpected reply from the TV during pairing");
    }

    @Override
    public void close() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }
}
