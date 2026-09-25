import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;

/** Offline maintainer utility. Private signing material must never be committed. */
class SignTestApk {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("input.apk output.apk private.p12 password-file");
        char[] password = Files.readString(Path.of(args[3]), StandardCharsets.UTF_8).strip().toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(args[2])) { store.load(in, password); }
        String alias = "modkit-test";
        X509Certificate certificate = (X509Certificate) store.getCertificate(alias);
        ApkSigner.SignerConfig config = new ApkSigner.SignerConfig.Builder(alias,
            (PrivateKey) store.getKey(alias, password), List.of(certificate)).build();
        new ApkSigner.Builder(List.of(config)).setInputApk(new File(args[0])).setOutputApk(new File(args[1]))
            .setMinSdkVersion(26).setV1SigningEnabled(false).setV2SigningEnabled(true)
            .setV3SigningEnabled(true).setV4SigningEnabled(false).setOtherSignersSignaturesPreserved(false).build().sign();
        ApkVerifier.Result verification = new ApkVerifier.Builder(new File(args[1])).build().verify();
        if (!verification.isVerified()) throw new IllegalStateException(verification.getErrors().toString());
        if (verification.getSignerCertificates().size() != 1 ||
            !verification.getSignerCertificates().get(0).equals(certificate)) throw new IllegalStateException("Unexpected signer");
        System.out.println("APK signature verified; v2=" + verification.isVerifiedUsingV2Scheme() +
            "; v3=" + verification.isVerifiedUsingV3Scheme());
        System.out.println("Certificate SHA-256: " + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())));
    }
}
