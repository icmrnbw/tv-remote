package io.github.icmrnbw.tvremote.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * The phone's identity towards the TV: an RSA key and a self-signed certificate. The TV
 * remembers this certificate when pairing, so it has to stay the same between launches.
 */
public final class ClientIdentity {
    private static final String ALIAS = "client";

    public final PrivateKey privateKey;
    public final X509Certificate certificate;

    private ClientIdentity(PrivateKey privateKey, X509Certificate certificate) {
        this.privateKey = privateKey;
        this.certificate = certificate;
    }

    public static ClientIdentity generate(String commonName) throws GeneralSecurityException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        KeyPair pair = gen.generateKeyPair();
        return new ClientIdentity(pair.getPrivate(), selfSigned(pair, commonName));
    }

    public static ClientIdentity load(byte[] pkcs8Key, byte[] certificateDer) throws GeneralSecurityException {
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8Key));
        return new ClientIdentity(key, parseCertificate(certificateDer));
    }

    public byte[] encodedKey() {
        return privateKey.getEncoded();
    }

    public byte[] encodedCertificate() throws GeneralSecurityException {
        return certificate.getEncoded();
    }

    /** Opens a TLS connection that presents our certificate and accepts the TV's self-signed one. */
    public SSLSocket connect(String host, int port, int timeoutMs) throws IOException {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(new KeyManager[] {new FixedKeyManager()}, new TrustManager[] {new TrustAnyServer()},
                    new SecureRandom());
            Socket plain = new Socket();
            plain.connect(new InetSocketAddress(host, port), timeoutMs);
            SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(plain, host, port, true);
            socket.setSoTimeout(timeoutMs);
            socket.startHandshake();
            return socket;
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private final class FixedKeyManager extends X509ExtendedKeyManager {
        @Override public String[] getClientAliases(String keyType, Principal[] issuers) { return new String[] {ALIAS}; }
        @Override public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) { return ALIAS; }
        @Override public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) { return ALIAS; }
        @Override public String[] getServerAliases(String keyType, Principal[] issuers) { return null; }
        @Override public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) { return null; }
        @Override public X509Certificate[] getCertificateChain(String alias) { return new X509Certificate[] {certificate}; }
        @Override public PrivateKey getPrivateKey(String alias) { return privateKey; }
    }

    // The TV's certificate is self-signed and only checked through the pairing code.
    @SuppressWarnings("TrustAllX509TrustManager")
    private static final class TrustAnyServer implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    // --- Minimal DER encoding for a self-signed X.509 certificate ---

    private static final byte[] OID_SHA256_WITH_RSA =
            {0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B};
    private static final byte[] OID_COMMON_NAME = {0x06, 0x03, 0x55, 0x04, 0x03};
    private static final byte[] DER_NULL = {0x05, 0x00};

    private static X509Certificate selfSigned(KeyPair pair, String commonName) throws GeneralSecurityException {
        SimpleDateFormat utc = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        String notBefore = utc.format(new Date(System.currentTimeMillis() - 24L * 3600 * 1000));

        byte[] serial = new byte[8];
        new SecureRandom().nextBytes(serial);
        serial[0] = (byte) ((serial[0] & 0x3F) | 0x40);

        byte[] algorithm = tlv(0x30, OID_SHA256_WITH_RSA, DER_NULL);
        byte[] name = tlv(0x30, tlv(0x31, tlv(0x30, OID_COMMON_NAME,
                tlv(0x0C, commonName.getBytes(StandardCharsets.UTF_8)))));
        byte[] validity = tlv(0x30, tlv(0x17, ascii(notBefore)), tlv(0x17, ascii("491231235959Z")));
        byte[] tbs = tlv(0x30,
                tlv(0xA0, tlv(0x02, new byte[] {2})),
                tlv(0x02, serial),
                algorithm,
                name,
                validity,
                name,
                pair.getPublic().getEncoded());

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(tbs);
        byte[] signature = signer.sign();
        byte[] bitString = new byte[signature.length + 1];
        System.arraycopy(signature, 0, bitString, 1, signature.length);

        return parseCertificate(tlv(0x30, tbs, algorithm, tlv(0x03, bitString)));
    }

    private static X509Certificate parseCertificate(byte[] der) throws GeneralSecurityException {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] tlv(int tag, byte[]... parts) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        for (byte[] p : parts) content.write(p, 0, p.length);
        int length = content.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        if (length < 0x80) {
            out.write(length);
        } else {
            byte[] lengthBytes = BigInteger.valueOf(length).toByteArray();
            int start = lengthBytes[0] == 0 ? 1 : 0;
            out.write(0x80 | (lengthBytes.length - start));
            out.write(lengthBytes, start, lengthBytes.length - start);
        }
        byte[] body = content.toByteArray();
        out.write(body, 0, body.length);
        return out.toByteArray();
    }
}
