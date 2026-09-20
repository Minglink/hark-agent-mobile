package com.openminis.app.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Fixed crypto vectors for `harkbak-enc/1` — the Android-side regression
 * anchor for the encryption layer.
 *
 * History: these expectations were originally byte-for-byte cross-platform
 * vectors produced by the REAL iOS primitives (CommonCrypto
 * `CCKeyDerivationPBKDF` + CryptoKit `HKDF`/`HMAC`/`AES.GCM`) over the same
 * fixed inputs, proving the Android port agreed with iOS. During the 2026-02
 * white-label split the HKDF info strings were renamed (minisbak-prefixed
 * infos became harkbak-prefixed ones, and the verifier label followed),
 * which deliberately forks the key hierarchy: packages written before this
 * change can no longer be opened here, and this build no longer opens iOS
 * packages. That break was accepted as part of the split.
 *
 * The hex values below were computed from the exact same fixed inputs with
 * an independent implementation of the new derivation (PBKDF2-HMAC-SHA256 →
 * RFC 5869 HKDF-SHA256 with empty salt, info per subkey), then cross-checked
 * by reproducing the legacy iOS vectors byte-for-byte with the same script
 * before the info swap. So they still pin the full derivation chain —
 * PBKDF2 parameters, HKDF extraction/expand, the verifier truncation —
 * against a second implementation, just not against iOS's live output.
 *
 * The iteration count here is 1000, not the shipped 600 000: iterations are
 * a plain loop parameter of PBKDF2 that travels in `kdf.iterations`, and a
 * 600k-round KDF in a unit test costs a second per assertion for no extra
 * coverage.
 *
 * If one of these fails, the derivation logic has drifted — treat it as a
 * format break, not something to paper over by updating the constant.
 */
class BackupCryptoInteropTest {

    private val passphrase = "correct horse battery staple"
    private val saltB64 = "AAECAwQFBgcICQoLDA0ODw=="
    private val iterations = 1000

    private fun keys(): BackupCrypto.Keys = BackupCrypto.deriveKeys(
        passphrase,
        BackupManifest.Encryption.KDF(
            alg = "pbkdf2-hmac-sha256", salt = saltB64, iterations = iterations
        ),
    )

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun `subkeys match the harkbak derivation vectors`() {
        val k = keys()
        assertEquals(
            "c4aea6869f2346d8fd98f2dd9a1b79d6904dac8298122a9e033ead6cf7c1d39e",
            k.dataKey.hex(),
        )
        assertEquals(
            "56f6ec2835a5f29bcc2d591c0d71417ab8289e5ac2dfd876caf7f2c7318a9909",
            k.secretsKey.hex(),
        )
        assertEquals(
            "5eca9a3e1b0c4025927bf09b42421354b25adcacf814017acb0e479a4e53a009",
            k.macKey.hex(),
        )
        assertEquals(
            "573f5b075c3cc8a780a389500ded28cd288e29b38759f89626fb90f285743b45",
            k.verifierKey.hex(),
        )
    }

    @Test
    fun `verifier matches the harkbak vector, so a wrong passphrase fails fast`() {
        val k = keys()
        assertEquals("e6dO5bx1XC0pIQU74Tgptg==", k.verifier)
        assertTrue(BackupCrypto.verifierMatches("e6dO5bx1XC0pIQU74Tgptg==", k))
        assertTrue(!BackupCrypto.verifierMatches("AAAAAAAAAAAAAAAAAAAAAA==", k))
    }

    @Test
    fun `manifest sidecar MAC matches the vector over identical raw bytes`() {
        val k = keys()
        val raw = """{"format":"harkbak/1","backup_id":"vector"}""".toByteArray(Charsets.UTF_8)
        assertEquals("AffK/VK5qL/MwF/D95tqm2pyTQwkDr7A8gvMsMq2EB4=", BackupCrypto.manifestMac(raw, k.macKey))
        // Must not throw for the good MAC…
        BackupCrypto.verifyManifestMac(raw, "AffK/VK5qL/MwF/D95tqm2pyTQwkDr7A8gvMsMq2EB4=", k.macKey)
        // …and must reject an edited manifest.
        val tampered = """{"format":"harkbak/1","backup_id":"forged"}""".toByteArray(Charsets.UTF_8)
        var threw = false
        try {
            BackupCrypto.verifyManifestMac(
                tampered, "AffK/VK5qL/MwF/D95tqm2pyTQwkDr7A8gvMsMq2EB4=", k.macKey
            )
        } catch (e: BackupCrypto.ManifestTamperedException) {
            threw = true
        }
        assertTrue("a tampered manifest must fail authentication", threw)
    }

    /**
     * Member framing proof: seals a member with [BackupCrypto.encryptFile]
     * and verifies the wire shape end-to-end — the `MBK1` magic prefix, the
     * big-endian segment length, the nonce‖ciphertext‖tag layout, and the
     * `"<path>#<segment>"` AAD binding — by decrypting it back through
     * [BackupCrypto.decryptStream].
     */
    @Test
    fun `seals and reopens a member with the MBK1 framing intact`() {
        val k = keys()
        val plain = "hello harkbak — 跨平台备份\n".toByteArray(Charsets.UTF_8)
        val source = createTempFile().apply { writeBytes(plain) }
        val member = createTempFile()
        BackupCrypto.encryptFile(source, member, k.dataKey, "data/sessions.jsonl.enc")

        val bytes = member.readBytes()
        assertArrayEquals(BackupCrypto.MAGIC, bytes.copyOf(4))

        val out = ByteArrayOutputStream()
        BackupCrypto.decryptStream(
            ByteArrayInputStream(bytes, 4, bytes.size - 4),
            out, k.dataKey, "data/sessions.jsonl.enc",
        )
        assertEquals("hello harkbak — 跨平台备份\n", out.toString("UTF-8"))
        listOf(source, member).forEach { it.delete() }
    }

    /**
     * The AAD binds a member to its path, so an attacker cannot swap an old
     * `sessions.jsonl.enc` in under a different name — §5.3's rename-replay
     * defence. A wrong path must fail authentication, not silently decrypt.
     */
    @Test
    fun `refuses a member decrypted under the wrong path`() {
        val k = keys()
        val source = createTempFile().apply { writeBytes("attack at dawn".toByteArray(Charsets.UTF_8)) }
        val member = createTempFile()
        BackupCrypto.encryptFile(source, member, k.dataKey, "data/sessions.jsonl.enc")
        val bytes = member.readBytes()
        var threw = false
        try {
            BackupCrypto.decryptStream(
                ByteArrayInputStream(bytes, 4, bytes.size - 4),
                ByteArrayOutputStream(), k.dataKey, "data/skills.jsonl.enc",
            )
        } catch (e: BackupCrypto.CorruptMemberException) {
            threw = true
        }
        assertTrue("a renamed member must fail its AAD check", threw)
        listOf(source, member).forEach { it.delete() }
    }

    /**
     * Round-trip across the segment boundary: more than one 4 MiB segment
     * exercises the per-segment AAD index, which is what stops segments being
     * reordered or dropped within a member.
     */
    @Test
    fun `round-trips a multi-segment member`() {
        val k = keys()
        val plain = ByteArray(BackupCrypto.SEGMENT_SIZE + 12345) { (it % 251).toByte() }
        val source = createTempFile()
        val encrypted = createTempFile()
        val decrypted = createTempFile()
        source.writeBytes(plain)

        BackupCrypto.encryptFile(source, encrypted, k.dataKey, "blobs/ab/deadbeef")
        BackupCrypto.decryptFile(encrypted, decrypted, k.dataKey, "blobs/ab/deadbeef")

        assertArrayEquals(plain, decrypted.readBytes())
        listOf(source, encrypted, decrypted).forEach { it.delete() }
    }

    @Test
    fun `an unknown KDF is refused loudly, never silently substituted`() {
        var threw = false
        try {
            BackupCrypto.deriveKeys(
                passphrase,
                BackupManifest.Encryption.KDF(alg = "argon2id", salt = saltB64, mKib = 65536, t = 3, p = 1),
            )
        } catch (e: BackupCrypto.UnsupportedKDFException) {
            threw = true
        }
        assertTrue("an unknown alg must throw rather than fall back to PBKDF2", threw)
    }

    private fun createTempFile() =
        java.io.File.createTempFile("harkbak-test", null).apply { deleteOnExit() }
}
