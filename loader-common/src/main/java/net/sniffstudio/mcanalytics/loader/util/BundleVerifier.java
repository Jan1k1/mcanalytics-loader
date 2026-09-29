package net.sniffstudio.mcanalytics.loader.util;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Checks Ed25519 signatures over connector jars against a list of trusted public keys.
 *
 * <p>The signature is over the raw bytes of the jar. A jar is accepted when any key in the list
 * verifies it, so a key can be rotated by shipping a loader that trusts both the old and the new
 * key for a while. Only JDK classes are used.
 */
public final class BundleVerifier {

    /**
     * The keys this loader trusts, as base64 of the X.509 SubjectPublicKeyInfo DER encoding. To
     * rotate the signing key, add the new key here, release a loader, and remove the old key in a
     * later release.
     */
    public static final List<String> TRUSTED_PUBLIC_KEYS_BASE64 = List.of(
            "MCowBQYDK2VwAyEA4WF07jmJtYyWBocGaLb2AddoDe8nFVqzUDLlHbNLx4A="
    );

    /** An Ed25519 signature is always 64 bytes. */
    public static final int SIGNATURE_LENGTH = 64;

    private final List<PublicKey> trustedKeys;

    public BundleVerifier(List<PublicKey> trustedKeys) {
        this.trustedKeys = List.copyOf(trustedKeys);
    }

    /** The verifier the shipped loader uses: {@link #TRUSTED_PUBLIC_KEYS_BASE64} and nothing else. */
    public static BundleVerifier production() {
        List<PublicKey> keys = new ArrayList<>();
        for (String encoded : TRUSTED_PUBLIC_KEYS_BASE64) {
            keys.add(decodePublicKey(encoded));
        }
        return new BundleVerifier(keys);
    }

    public static PublicKey decodePublicKey(String base64X509) {
        try {
            byte[] der = Base64.getDecoder().decode(base64X509);
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Invalid trusted public key", e);
        }
    }

    public int trustedKeyCount() {
        return trustedKeys.size();
    }

    /** True when the text is base64 that decodes to exactly {@value #SIGNATURE_LENGTH} bytes. */
    public static boolean isWellFormedSignature(String signatureBase64) {
        return decodeSignature(signatureBase64) != null;
    }

    private static byte[] decodeSignature(String signatureBase64) {
        if (signatureBase64 == null || signatureBase64.isEmpty() || signatureBase64.length() > 128) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(signatureBase64);
            return raw.length == SIGNATURE_LENGTH ? raw : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * @return true only when the base64 signature is well formed and verifies {@code data} under
     *         at least one trusted key; false for anything else, including an empty key list
     */
    public boolean verify(byte[] data, String signatureBase64) {
        byte[] signature = decodeSignature(signatureBase64);
        if (signature == null || data == null) {
            return false;
        }
        for (PublicKey key : trustedKeys) {
            try {
                Signature verifier = Signature.getInstance("Ed25519");
                verifier.initVerify(key);
                verifier.update(data);
                if (verifier.verify(signature)) {
                    return true;
                }
            } catch (GeneralSecurityException | RuntimeException ignored) {
                // Try the next key.
            }
        }
        return false;
    }
}
