package net.sniffstudio.mcanalytics.loader.testsupport;

import net.sniffstudio.mcanalytics.loader.api.LoaderLogger;
import net.sniffstudio.mcanalytics.loader.lifecycle.BundleManager;
import net.sniffstudio.mcanalytics.loader.util.BundleVerifier;
import net.sniffstudio.mcanalytics.loader.util.ChecksumUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;

/** A throwaway Ed25519 key for tests, with helpers to sign bytes and to seed the jar cache. */
public final class SigningFixture {

    public static final LoaderLogger SILENT = new LoaderLogger() {
        @Override
        public void info(String message) {}

        @Override
        public void warn(String message) {}

        @Override
        public void error(String message) {}

        @Override
        public void error(String message, Throwable throwable) {}
    };

    private final KeyPair keyPair;

    public SigningFixture() {
        this(newKeyPair());
    }

    private SigningFixture(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    public static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public KeyPair keyPair() {
        return keyPair;
    }

    /** A verifier that trusts only this fixture's key. */
    public BundleVerifier verifier() {
        return new BundleVerifier(List.of(keyPair.getPublic()));
    }

    public String sign(byte[] data) {
        return sign(keyPair, data);
    }

    public static String sign(KeyPair key, byte[] data) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key.getPrivate());
            signature.update(data);
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Writes a signed jar and its sidecar into {@code <dataDir>/cache}, as an install would. */
    public Path seedCache(Path dataDir, String platform, String version, byte[] jarBytes) throws IOException {
        BundleManager manager = new BundleManager(dataDir, verifier(), SILENT);
        Path jar = manager.getBundlePath(platform, version);
        Files.write(jar, jarBytes);
        manager.writeSidecar(jar, ChecksumUtil.sha256(jarBytes), sign(jarBytes));
        return jar;
    }
}
