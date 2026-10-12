package org.privateview.quietscreen.core

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class UpdateManifestTest {
    private val fields = listOf("QUIETSCREEN_UPDATE_V1", "org.privateview.quietscreen", "2", "1.1.0",
        "26", "1024", "AB".repeat(32), "https://github.com/example/quietscreen/releases/download/v1.1.0/quietscreen.apk")

    private fun payload(changes: Map<Int, String> = emptyMap()): ByteArray =
        fields.mapIndexed { index, value -> changes[index] ?: value }.joinToString("\n").toByteArray(Charsets.US_ASCII)

    private fun signed(payload: ByteArray = payload(), pair: KeyPair = TestKeyPair.primary): ByteArray {
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(pair.private)
            update(payload)
            sign()
        }
        return envelope(payload, signature)
    }

    private fun envelope(payload: ByteArray, signature: ByteArray): ByteArray =
        ("QUIETSCREEN_SIGNED_UPDATE_V1\n" + Base64.getEncoder().encodeToString(payload) + "\n" +
            Base64.getEncoder().encodeToString(signature)).toByteArray(Charsets.US_ASCII)

    private fun verify(bytes: ByteArray, current: Long = 1, sdk: Int = 35, expected: String = fields[1],
        pair: KeyPair = TestKeyPair.primary): UpdateDescriptor? =
        UpdateManifest.verify(bytes, pair.public, expected, current, sdk, "example", "quietscreen")

    private fun rejects(code: String? = null, action: () -> Unit) {
        try {
            action()
            fail("Expected rejection")
        } catch (failure: IllegalArgumentException) {
            if (code != null) assertEquals(code, failure.message)
            assertTrue(failure.message.orEmpty().matches(Regex("UPDATE_[A-Z]+")))
            assertNull(failure.cause)
        }
    }

    @Test fun verifiesSignedMetadataAndCanonicalEnvelopeDelimiters() {
        val bytes = signed()
        val descriptor = verify(bytes)!!
        assertEquals(UpdateDescriptor(fields[1], 2, "1.1.0", 26, 1024, "ab".repeat(32), fields[7]), descriptor)
        assertEquals(descriptor, verify(bytes + byteArrayOf(10)))
        assertEquals(descriptor, verify(bytes.toString(Charsets.US_ASCII).replace("\n", "\r\n")
            .plus("\r\n").toByteArray(Charsets.US_ASCII)))
    }

    @Test fun rejectsWrongKeyAndTamperingBeforeInterpretingPayload() {
        rejects("UPDATE_SIGNATURE") { verify(signed(), pair = TestKeyPair.other) }
        val original = signed().toString(Charsets.US_ASCII).split('\n')
        val tampered = "QUIETSCREEN_SIGNED_UPDATE_V1\n" +
            Base64.getEncoder().encodeToString(payload(mapOf(0 to "invalid", 2 to "secret-value"))) +
            "\n" + original[2]
        rejects("UPDATE_SIGNATURE") { verify(tampered.toByteArray(Charsets.US_ASCII)) }
    }

    @Test fun rejectsMalformedAndOversizedUnsignedEnvelopes() {
        val good = signed()
        val malformed = listOf(ByteArray(UpdateManifest.MAX_ENVELOPE_BYTES + 1), byteArrayOf(-1),
            "QUIETSCREEN_SIGNED_UPDATE_V1\n!\nAAAA".toByteArray(),
            "QUIETSCREEN_SIGNED_UPDATE_V1\nYQ==\nAB==".toByteArray(),
            good + "\n\n".toByteArray(), good + "\r".toByteArray(), ByteArray(0))
        malformed.forEach { rejects("UPDATE_ENVELOPE") { verify(it) } }
    }

    @Test fun rejectsSmallRsaKeys() {
        val small = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        rejects("UPDATE_KEY") { verify(signed(pair = small), pair = small) }
    }

    @Test fun olderOrEqualVersionsAreIgnoredOnlyAfterAllFieldsPassValidation() {
        assertNull(verify(signed(), current = 2))
        assertNull(verify(signed(), current = 3))
        rejects("UPDATE_URL") { verify(signed(payload(mapOf(7 to "https://github.com/example/other/releases/download/v1/a.apk"))), current = 3) }
        rejects("UPDATE_PACKAGE") { verify(signed(), expected = "org.privateview.other") }
    }

    @Test fun enforcesNumericAndVersionBounds() {
        listOf("0", "-1", "2147483648", "9223372036854775808", "2\r", "2\n3", "02", "2.0")
            .forEach { rejects { verify(signed(payload(mapOf(2 to it)))) } }
        listOf("", "-bad", "a".repeat(65), "1 2", "1/2")
            .forEach { rejects { verify(signed(payload(mapOf(3 to it)))) } }
        listOf("0", "67108865", "9223372036854775808")
            .forEach { rejects { verify(signed(payload(mapOf(5 to it)))) } }
        assertEquals(UpdateManifest.MAX_APK_BYTES, verify(signed(payload(mapOf(5 to "67108864"))))!!.apkBytes)
        assertEquals(1L, verify(signed(payload(mapOf(5 to "1"))))!!.apkBytes)
        rejects("UPDATE_SDK") { verify(signed(), sdk = 25) }
        listOf("0", "101", "-1").forEach { rejects { verify(signed(payload(mapOf(4 to it)))) } }
        assertNotNull(verify(signed(), sdk = 26))
        rejects { verify(signed(payload(mapOf(6 to "g".repeat(64))))) }
    }

    @Test fun rejectsNoncanonicalSignedPayloads() {
        rejects { verify(signed(payload() + byteArrayOf(10))) }
        rejects { verify(signed(payload().toString(Charsets.US_ASCII).replace("\n", "\r\n").toByteArray())) }
        rejects { verify(signed(payload(mapOf(1 to "org..quietscreen")))) }
        rejects { verify(signed(payload().copyOf().apply { this[0] = -1 })) }
    }

    @Test fun urlValidationPinsRepositoryAndRejectsAlternateDestinationsAndInjection() {
        val url = fields[7]
        assertTrue(UpdateManifest.validateApkUrl(url, "example", "quietscreen"))
        assertTrue(UpdateManifest.validateApkUrl(url.replace("github.com/", "github.com:443/"), "example", "quietscreen"))
        val bad = listOf(url.replace("https:", "http:"), url.replace("github.com", "github.com.example"),
            url.replace("github.com", "user@github.com"), url.replace("github.com", "github.com:444"),
            url.replace("/example/", "/other/"), url.replace("/quietscreen/", "/other/"),
            url + "?download=1", url + "#fragment", url.replace("v1.1.0", "%76"),
            url.replace("v1.1.0", ".."), url.replace("v1.1.0", "v1/extra"),
            url.replace("quietscreen.apk", "quietscreen.zip"), url + "/", url + "\n", url + " ",
            url.replace("v1.1.0", "v1\\evil"))
        bad.forEach {
            assertFalse(UpdateManifest.validateApkUrl(it, "example", "quietscreen"))
            rejects { verify(signed(payload(mapOf(7 to it)))) }
        }
        assertFalse(UpdateManifest.validateRepository("-example", "quietscreen"))
        assertFalse(UpdateManifest.validateRepository("example", "../quietscreen"))
    }

    /** Test-only keys are generated in memory; no private signing material is shipped. */
    private object TestKeyPair {
        private fun generate(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val primary = generate()
        val other = generate()
    }
}
