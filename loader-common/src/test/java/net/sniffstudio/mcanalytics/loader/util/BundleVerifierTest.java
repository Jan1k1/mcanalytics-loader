package net.sniffstudio.mcanalytics.loader.util;

import net.sniffstudio.mcanalytics.loader.testsupport.SigningFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BundleVerifierTest {

    private static final byte[] DATA = "jar-bytes".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("The embedded production key is a valid Ed25519 X.509 key")
    void productionKeyDecodes() {
        BundleVerifier production = BundleVerifier.production();

        assertThat(production.trustedKeyCount()).isEqualTo(BundleVerifier.TRUSTED_PUBLIC_KEYS_BASE64.size()).isEqualTo(1);
        PublicKey key = BundleVerifier.decodePublicKey("MCowBQYDK2VwAyEA4WF07jmJtYyWBocGaLb2AddoDe8nFVqzUDLlHbNLx4A=");
        assertThat(key.getAlgorithm()).isIn("Ed25519", "EdDSA");
        assertThat(key.getFormat()).isEqualTo("X.509");
    }

    @Test
    @DisplayName("The production verifier rejects a signature made with a test key")
    void productionRejectsATestKeySignature() {
        SigningFixture other = new SigningFixture();
        assertThat(BundleVerifier.production().verify(DATA, other.sign(DATA))).isFalse();
    }

    @Test
    @DisplayName("A good signature verifies; changed data, another key or bad base64 do not")
    void verifiesOnlyGoodSignatures() {
        SigningFixture fixture = new SigningFixture();
        BundleVerifier verifier = fixture.verifier();
        String signature = fixture.sign(DATA);

        assertThat(verifier.verify(DATA, signature)).isTrue();
        assertThat(verifier.verify("jar-bytez".getBytes(StandardCharsets.UTF_8), signature)).isFalse();
        assertThat(verifier.verify(DATA, new SigningFixture().sign(DATA))).isFalse();
        assertThat(verifier.verify(DATA, null)).isFalse();
        assertThat(verifier.verify(DATA, "")).isFalse();
        assertThat(verifier.verify(DATA, "not base64 !!")).isFalse();
        assertThat(verifier.verify(DATA, Base64.getEncoder().encodeToString(new byte[10]))).isFalse();
        assertThat(verifier.verify(DATA, Base64.getEncoder().encodeToString(new byte[64]))).isFalse();
        assertThat(verifier.verify(null, signature)).isFalse();
    }

    @Test
    @DisplayName("A flipped bit in the signature is rejected")
    void rejectsAFlippedSignatureBit() {
        SigningFixture fixture = new SigningFixture();
        byte[] signature = Base64.getDecoder().decode(fixture.sign(DATA));
        signature[10] ^= 1;

        assertThat(fixture.verifier().verify(DATA, Base64.getEncoder().encodeToString(signature))).isFalse();
    }

    @Test
    @DisplayName("With no trusted keys nothing verifies")
    void emptyKeyListTrustsNothing() {
        SigningFixture fixture = new SigningFixture();
        assertThat(new BundleVerifier(List.of()).verify(DATA, fixture.sign(DATA))).isFalse();
    }

    @Test
    @DisplayName("Any key in the list can verify, which allows key rotation")
    void anyTrustedKeyVerifies() {
        SigningFixture oldKey = new SigningFixture();
        SigningFixture newKey = new SigningFixture();
        BundleVerifier both = new BundleVerifier(List.of(oldKey.keyPair().getPublic(), newKey.keyPair().getPublic()));

        assertThat(both.verify(DATA, oldKey.sign(DATA))).isTrue();
        assertThat(both.verify(DATA, newKey.sign(DATA))).isTrue();
        assertThat(oldKey.verifier().verify(DATA, newKey.sign(DATA))).isFalse();
    }

    @Test
    @DisplayName("An invalid trusted key fails loudly instead of trusting nothing silently")
    void invalidKeyIsAnError() {
        assertThatThrownBy(() -> BundleVerifier.decodePublicKey("AAAA"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Signature shape check accepts exactly 64 base64 bytes")
    void signatureShape() {
        assertThat(BundleVerifier.isWellFormedSignature(Base64.getEncoder().encodeToString(new byte[64]))).isTrue();
        assertThat(BundleVerifier.isWellFormedSignature(Base64.getEncoder().encodeToString(new byte[63]))).isFalse();
        assertThat(BundleVerifier.isWellFormedSignature(null)).isFalse();
        assertThat(BundleVerifier.isWellFormedSignature("")).isFalse();
    }
}
