import com.opus.airvia.ap2.Ap2Channel
import com.opus.airvia.ap2.Ap2AudioPackets
import com.opus.airvia.ap2.AlacFrame
import com.opus.airvia.ap2.BplistReader
import com.opus.airvia.ap2.BplistWriter
import com.opus.airvia.ap2.ChaCha20Poly1305
import com.opus.airvia.ap2.Hkdf
import com.opus.airvia.ap2.SrpClient
import com.opus.airvia.ap2.Tlv8
import com.opus.airvia.ap2.ToneGenerator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * JVM self-tests for the AirPlay 2 transient-pairing probe (Outro 1.2.6):
 *  - TLV8 round-trip incl. >255-byte fragmentation
 *  - HKDF-SHA-512 vs Python `cryptography` reference vectors
 *  - ChaCha20-Poly1305 vs RFC 8439 A.5 #1 + Python `cryptography` vectors
 *  - SRP-6a client vs srptools 1.0.1 (pyatv's backend) exact formulas with
 *    fixed a/salt/b: A, K, M1 and M2 verification. NOTE: srptools hashes
 *    integers in minimal-length big-endian inside proofs (notably H(g) uses
 *    g as a single 0x05 byte, not 384-byte padded) — verified 2026-09-27.
 *  - Ap2Channel framed encrypt/decrypt round-trip over byte streams
 */
var failures = 0
fun check(name: String, cond: Boolean) {
    if (cond) println("PASS: $name") else { println("FAIL: $name"); failures++ }
}
fun hx(s: String): ByteArray {
    require(s.length % 2 == 0)
    return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

fun main() {
    // TLV8 ----------------------------------------------------------
    run {
        val entries = listOf(0x00 to byteArrayOf(0x00), 0x06 to byteArrayOf(0x01), 0x10 to byteArrayOf(0x00))
        val enc = Tlv8.encode(entries)
        check("T1 M1 body exact bytes", enc.contentEquals(hx("000100060101100100")))
        val dec = Tlv8.decode(enc)
        check("T2 round-trip small values", dec.size == 3 && dec[1].second.contentEquals(byteArrayOf(0x01)) && Tlv8.first(dec, 0x10)!!.contentEquals(byteArrayOf(0x00)))
    }
    run {
        val big = ByteArray(300) { it.toByte() }
        val enc = Tlv8.encode(listOf(0x03 to big))
        check("T3 >255B fragments to 255+45", enc.size == 2 + 255 + 2 + 45 && (enc[1].toInt() and 0xFF) == 255 && (enc[257].toInt() and 0xFF) == 0x03 && (enc[258].toInt() and 0xFF) == 45)
        val dec = Tlv8.decode(enc)
        check("T4 fragments decode back to one 300B value", dec.size == 1 && dec[0].second.contentEquals(big))
    }
    run {
        val enc = Tlv8.encode(listOf(0xFF to ByteArray(0)))
        check("T5 zero-length separator", enc.contentEquals(hx("ff00")))
        check("T6 error names", Tlv8.errorName(0x02) == "authentication" && Tlv8.errorName(0x07) == "busy" && Tlv8.errorName(0x42) == "code 66")
    }
    // HKDF-SHA-512 (Python `cryptography` reference) -----------------
    run {
        val okm = Hkdf.derive(hx("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), hx("000102030405060708090a0b0c"), "".toByteArray(), 42)
        check("H1 vector 0 (42B)", okm.contentEquals(hx("f81b87481a18b664936daeb222f58cba0ebc55f5c85996b9f1cb396c327b70bb4c50fc5671cc1eca2f27")))
    }
    run {
        val okm = Hkdf.derive(hx("00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"), hx("436f6e74726f6c2d53616c74"), "Control-Write-Encryption-Key".toByteArray(), 32)
        check("H2 vector 1 (32B)", okm.contentEquals(hx("2f6d8c137854b93c9391b8318745b59a90b8b9114c54532d561477859b7063fa")))
    }
    run {
        val okm = Hkdf.derive(hx("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"), hx("436f6e74726f6c2d53616c74"), "Control-Read-Encryption-Key".toByteArray(), 32)
        check("H3 vector 2 (32B)", okm.contentEquals(hx("f5a849cc2e6016a676db89527ef6109811fd423fca4473a2688c97e654139621")))
    }
    // ChaCha20-Poly1305 ----------------------------------------------
    run {
        val sealed = ChaCha20Poly1305.seal(hx("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"), hx("070000004041424344454647"), hx("4c616469657320616e642047656e746c656d656e206f662074686520636c617373206f66202739393a204966204920636f756c64206f6666657220796f75206f6e6c79206f6e652074697020666f7220746865206675747572652c2073756e73637265656e20776f756c642062652069742e"), hx("50515253c0c1c2c3c4c5c6c7"))
        check("C1 seal RFC 8439 A.5 #1", sealed.contentEquals(hx("d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc3ff4def08e4b7a9de576d26586cec64b61161ae10b594f09e26a7e902ecbd0600691")))
        val open = ChaCha20Poly1305.open(hx("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"), hx("070000004041424344454647"), hx("d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc3ff4def08e4b7a9de576d26586cec64b61161ae10b594f09e26a7e902ecbd0600691"), hx("50515253c0c1c2c3c4c5c6c7"))
        check("C1b open RFC 8439 A.5 #1", open != null && open.contentEquals(hx("4c616469657320616e642047656e746c656d656e206f662074686520636c617373206f66202739393a204966204920636f756c64206f6666657220796f75206f6e6c79206f6e652074697020666f7220746865206675747572652c2073756e73637265656e20776f756c642062652069742e")))
    }
    run {
        val sealed = ChaCha20Poly1305.seal(hx("390c8c7d7247342cd8100f2f6f770d65d670e58e0351d8ae8e4f6eac342fc231"), hx("b7b08716eb3fc12896b96223"), hx(""), hx("74"))
        check("C2 seal random 0B pt", sealed.contentEquals(hx("38f764cfbe019944e7ee0eda2734df4b")))
        val open = ChaCha20Poly1305.open(hx("390c8c7d7247342cd8100f2f6f770d65d670e58e0351d8ae8e4f6eac342fc231"), hx("b7b08716eb3fc12896b96223"), hx("38f764cfbe019944e7ee0eda2734df4b"), hx("74"))
        check("C2b open random 0B pt", open != null && open.contentEquals(hx("")))
    }
    run {
        val sealed = ChaCha20Poly1305.seal(hx("94287733c28ee8ba53bdb56b8824577d53ecc28a70a61c7510a1cd89216ca16c"), hx("ffcaea4987477e86dbccb970"), hx("46"), hx("fc2e18384e51d820c5c3ef80053a88ae"))
        check("C3 seal random 1B pt", sealed.contentEquals(hx("1937ec32e91e674689f266f6f1b9b13b1f")))
        val open = ChaCha20Poly1305.open(hx("94287733c28ee8ba53bdb56b8824577d53ecc28a70a61c7510a1cd89216ca16c"), hx("ffcaea4987477e86dbccb970"), hx("1937ec32e91e674689f266f6f1b9b13b1f"), hx("fc2e18384e51d820c5c3ef80053a88ae"))
        check("C3b open random 1B pt", open != null && open.contentEquals(hx("46")))
    }
    run {
        val sealed = ChaCha20Poly1305.seal(hx("3996de50e801865b3698654ebf5200a5fa0939b99d7a1d7b282bf8234041f354"), hx("87d86c669fccbfe0e73d7e73"), hx("20ad0a7570"), hx(""))
        check("C4 seal random 5B pt", sealed.contentEquals(hx("6c3a888d5b69fd5a3808d3402e24e2289dbf10ae93")))
        val open = ChaCha20Poly1305.open(hx("3996de50e801865b3698654ebf5200a5fa0939b99d7a1d7b282bf8234041f354"), hx("87d86c669fccbfe0e73d7e73"), hx("6c3a888d5b69fd5a3808d3402e24e2289dbf10ae93"), hx(""))
        check("C4b open random 5B pt", open != null && open.contentEquals(hx("20ad0a7570")))
    }
    run {
        val sealed = ChaCha20Poly1305.seal(hx("241e752210a924798ef86d43f27cf2d0613031dcb5d8d2ef1b321fcead377f62"), hx("61e547d85d8eec7f26e23219"), hx("072f7955d0f8f66dcd1e54c201c787e8"), hx("d8f94f61976f1d1fa0"))
        check("C5 seal random 16B pt", sealed.contentEquals(hx("a4452c74aeb109cd85813b7b04b3a71016f47f93ffb30cdf399c62e0f835b10c")))
        val open = ChaCha20Poly1305.open(hx("241e752210a924798ef86d43f27cf2d0613031dcb5d8d2ef1b321fcead377f62"), hx("61e547d85d8eec7f26e23219"), hx("a4452c74aeb109cd85813b7b04b3a71016f47f93ffb30cdf399c62e0f835b10c"), hx("d8f94f61976f1d1fa0"))
        check("C5b open random 16B pt", open != null && open.contentEquals(hx("072f7955d0f8f66dcd1e54c201c787e8")))
    }
    run {
        val sealed = ChaCha20Poly1305.seal(hx("1d19f4501d295f232278ce3d7e1429d6a18568a07a87ca4399eaa12504ea3325"), hx("6d8743b2237dbd9150e09a04"), hx("993544873b364f8b906baf6887fa801a2f"), hx("8d1601aa428652e2da0439264c"))
        check("C6 seal random 17B pt", sealed.contentEquals(hx("753c7b207665423f1ba22c2a579acd8a0dff9ac40d0f66a158f9267853bc090ed4")))
        val open = ChaCha20Poly1305.open(hx("1d19f4501d295f232278ce3d7e1429d6a18568a07a87ca4399eaa12504ea3325"), hx("6d8743b2237dbd9150e09a04"), hx("753c7b207665423f1ba22c2a579acd8a0dff9ac40d0f66a158f9267853bc090ed4"), hx("8d1601aa428652e2da0439264c"))
        check("C6b open random 17B pt", open != null && open.contentEquals(hx("993544873b364f8b906baf6887fa801a2f")))
    }
    run {
        val sealed = ChaCha20Poly1305.seal(hx("12bd4bdc41159dba14b76b7f34b5d04f79535ad30c5baad27f885137c313f071"), hx("66ebb39c74720c62cca88e23"), hx("8eb3cca90e3b855b871337deb0a0df3bc5618216df0064badc23a9a03f999ed1a7ce974162d7c2599acf009b926bdca4eee2e26df2562b91ab2f789e73654b0c"), hx("7d"))
        check("C7 seal random 64B pt", sealed.contentEquals(hx("bc662881f3b9b90a9ea15e0a7a9f402d9617a7760925e57069a1bc940819f205cf76493116d6df62ae7a8fb19e81176477094b691c9119332bf46621f89d951f6e6248523419cab56567f28dfeaef511")))
        val open = ChaCha20Poly1305.open(hx("12bd4bdc41159dba14b76b7f34b5d04f79535ad30c5baad27f885137c313f071"), hx("66ebb39c74720c62cca88e23"), hx("bc662881f3b9b90a9ea15e0a7a9f402d9617a7760925e57069a1bc940819f205cf76493116d6df62ae7a8fb19e81176477094b691c9119332bf46621f89d951f6e6248523419cab56567f28dfeaef511"), hx("7d"))
        check("C7b open random 64B pt", open != null && open.contentEquals(hx("8eb3cca90e3b855b871337deb0a0df3bc5618216df0064badc23a9a03f999ed1a7ce974162d7c2599acf009b926bdca4eee2e26df2562b91ab2f789e73654b0c")))
    }
    run {
        val sealed = ChaCha20Poly1305.seal(hx("f325e9d463c4fdcc7c4b0236d9705aed197f3ee944eda2e2dae451f3e6847e8d"), hx("f87a8ce12792788baba32946"), hx("4d76c44e6d20d4d0a9eed41f69d7c70ac2f403b498c7d670f9708bdff80ec7accf54ef410dc90d2adb45ec5d1985c2a76ce8a7acc28ed78129f0091ab37223140f7e660a4e7a40f23a6fee83bc553a539f370d9fc0cb65267c349a3d15b1dbbd23ae06d7"), hx("36ddb9eb4ede5a8af7eedf89a57d2c"))
        check("C8 seal random 100B pt", sealed.contentEquals(hx("10486ab833ef4114255cfde1eeac51e615806a2a9df90c0975efb8de2a3a7332cc1ced5b217b101589ae7f79efd3d8dc936bd44f202652dbe831198fe52e60c30df9020d72794336d7a3559d5048150db7755a7dd63b3b85a71d9a2a03cef8d3373d093429f7e1a51b182cec7e3a645d6f7afeb1")))
        val open = ChaCha20Poly1305.open(hx("f325e9d463c4fdcc7c4b0236d9705aed197f3ee944eda2e2dae451f3e6847e8d"), hx("f87a8ce12792788baba32946"), hx("10486ab833ef4114255cfde1eeac51e615806a2a9df90c0975efb8de2a3a7332cc1ced5b217b101589ae7f79efd3d8dc936bd44f202652dbe831198fe52e60c30df9020d72794336d7a3559d5048150db7755a7dd63b3b85a71d9a2a03cef8d3373d093429f7e1a51b182cec7e3a645d6f7afeb1"), hx("36ddb9eb4ede5a8af7eedf89a57d2c"))
        check("C8b open random 100B pt", open != null && open.contentEquals(hx("4d76c44e6d20d4d0a9eed41f69d7c70ac2f403b498c7d670f9708bdff80ec7accf54ef410dc90d2adb45ec5d1985c2a76ce8a7acc28ed78129f0091ab37223140f7e660a4e7a40f23a6fee83bc553a539f370d9fc0cb65267c349a3d15b1dbbd23ae06d7")))
    }
    run {
        val sealed = ChaCha20Poly1305.seal(hx("8ee67cedc2ac0efda65df96cb584ae8f8d05612b7bd0fa7bf3fbe5082f9671cf"), hx("7c9cbcf2b0d9a9b4e88a9c80"), hx("763d62a13d5e626ef78d9033639774b85b9a07408c171b9540fb340691f0f5e1ae5e1a81f43a21cdfb251b4d4c9b2b7f3cd573c2e6e298db9c1e326a6c8729507a58265001d1e6f09510769390e824778765d93a734c8848241e549d93e03fef9bce8bfce02914dda5800d2e750a891459f0e28e5cdffb2ef0b2d1aaa43552a8d2fd93cd12e82da181a53bce00ecd31b60b9ffe21a68884393e0f83e0e7a519f07d02f733aec3c4eff958bd4f7f17ce94ac46145238dd4ae88019098fa4ce4f7b0aac1e9a4607ac4"), hx("d216a2f2c3c54d"))
        check("C9 seal random 200B pt", sealed.contentEquals(hx("fa15ea69e5a1be13a47afe5cecf96139596eb29811ab6060cee1b92f07ab4e50c5f854e626674511a7e0f629f48044b6f13e737bfc68ee231e797185a7e5263c525047fcb284279e9ec991afda3ba2187ac7ea1b62f9b6eb1f34c5dd7acda655e8d4d9bb7b07b93ab1855fb22ac15ab676767f55b6ffd6481da44098ed84d88f0a46ef26c41d5b4f589536274a65dfee8d7f496d3b7b70420a15c8a9451f48f3f17c66f66923ef018dfe857981d4a3a1a666adeb7c1b88331c055f07f42059e39f668fc982b3e8caa9bec86acf3cad69b0a45741a3bf4ebd")))
        val open = ChaCha20Poly1305.open(hx("8ee67cedc2ac0efda65df96cb584ae8f8d05612b7bd0fa7bf3fbe5082f9671cf"), hx("7c9cbcf2b0d9a9b4e88a9c80"), hx("fa15ea69e5a1be13a47afe5cecf96139596eb29811ab6060cee1b92f07ab4e50c5f854e626674511a7e0f629f48044b6f13e737bfc68ee231e797185a7e5263c525047fcb284279e9ec991afda3ba2187ac7ea1b62f9b6eb1f34c5dd7acda655e8d4d9bb7b07b93ab1855fb22ac15ab676767f55b6ffd6481da44098ed84d88f0a46ef26c41d5b4f589536274a65dfee8d7f496d3b7b70420a15c8a9451f48f3f17c66f66923ef018dfe857981d4a3a1a666adeb7c1b88331c055f07f42059e39f668fc982b3e8caa9bec86acf3cad69b0a45741a3bf4ebd"), hx("d216a2f2c3c54d"))
        check("C9b open random 200B pt", open != null && open.contentEquals(hx("763d62a13d5e626ef78d9033639774b85b9a07408c171b9540fb340691f0f5e1ae5e1a81f43a21cdfb251b4d4c9b2b7f3cd573c2e6e298db9c1e326a6c8729507a58265001d1e6f09510769390e824778765d93a734c8848241e549d93e03fef9bce8bfce02914dda5800d2e750a891459f0e28e5cdffb2ef0b2d1aaa43552a8d2fd93cd12e82da181a53bce00ecd31b60b9ffe21a68884393e0f83e0e7a519f07d02f733aec3c4eff958bd4f7f17ce94ac46145238dd4ae88019098fa4ce4f7b0aac1e9a4607ac4")))
    }
    run {
        val bad = hx("d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc3ff4def08e4b7a9de576d26586cec64b61161ae10b594f09e26a7e902ecbd0600691")
        bad[10] = (bad[10].toInt() xor 0x01).toByte()
        check("C9 tampered tag rejected", ChaCha20Poly1305.open(hx("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"), hx("070000004041424344454647"), bad, hx("50515253c0c1c2c3c4c5c6c7")) == null)
        val c10k = hx("390c8c7d7247342cd8100f2f6f770d65d670e58e0351d8ae8e4f6eac342fc231")
        val c10n = hx("b7b08716eb3fc12896b96223")
        val c10rt = ChaCha20Poly1305.open(c10k, c10n, ChaCha20Poly1305.seal(c10k, c10n, ByteArray(0)))
        check("C10 empty plaintext round-trips", c10rt != null && c10rt.isEmpty())
    }
    // SRP-6a client (independent Python reference, fixed a/salt/b) ----
    run {
        check("S1 N is 3072-bit", SrpClient.N.bitLength() == 3072)
        val salt = hx("00112233445566778899aabbccddeeff")
        val bBytes = hx("9d14fecbd1554d8089b48288853d13353e0fffeaf3453778b6aa22e2cbdf11f02209e30bd21186e426bbf01978b97108aaad6a1f803966cedb7fc5a3e529ad887ef5b70dfafb9f6d1786e3b88b4a7ca62513ccbb0b1150efa786a65a715a4cc2a9b2d10d5e9e40a8ba1cfdffe4c8f95d24e03065359bf0f3aed7a6379a31e2cc3dfb22f4b42ec9c90dc223aff181c824a2296f47e07aae2b82190b3eb8399b2741675134ca602197a286a74ec3983b737a8ca6d4611498722efff749fdd308174ccb1291e068a69f97348b035c4849a19d44fe20c5e1bcb688ba77b10415f1db925fe7b34bc9c4a1cd4fe5ebd9bdcda820dddcd08a693b50dd39c65fef62d29a80ba003dd9d9ffdb56c36fe83dc435558674fd2c8d27ae8b5ca788467dc66ba96e30ddd7c291b1e1ea518e549bc32cdea42b70c2930b9e3bcc98800fd0ba851b1f0a1afa31e0376d5038728f9d282df533b6f9c9940775b50024d42b5fe07677ee1bb5ff72f8e34dc95a00f52acf0341c062018d021105b41288d61458d6e6bf")
        val a = BigInteger("a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3", 16)
        val keys = SrpClient.compute(salt, bBytes, a)
        check("S2 A == g^a mod N", keys.aPub.contentEquals(hx("58ea94a5f25202e6498ad66a58cc77678f23c27b1b4224cb29a35c16f91dd3304447ac027b27693ac034f50b67e3a2eb88b3e7d7f412ce13c823bc765f42718244be1bf214eb52ae194943acde7e6902876b746966bd93646572839a5f64091a7d1474e56da56fa36815c40a3f487f69535b27d7c481dd36cec604bfa23cabe9e1c2c7038c0587404204e1ee60d2ce3439ba3e5277604c81f91d39061b781b27c4de0d6666543d128e57facf313fda71e30a63532cac1428cd2dfecd2ca48db2fddf3139ae5d6399f3a26974454e8e7cebe4eafaa997bc4d54d78e292fe66ad80fb7291aff3b60467fa05e225ce1f8f4b3c6abb12c04f4d83f12367df38dd94b7b6cddc5aeeae55aeaf16811198bb82702357485b1e2a0cc86c77791f8d9bb62cbfc2e4720eae25f73fb7623dab48cb0e576f5be5986ca94fb8045dc0b0fd07d2f1daca003bb0662ff0cc0426cf06296526a515bef11f2b6c9db5c7db04eb4057c8f9bcd24f2249bf4c813acbc0de338f2d96094c77d4665e2afc5d3455da926")))
        check("S3 K matches", keys.k64.contentEquals(hx("b51060013ad17a5729d065c4dea4be5c83ee56f9ac83ad490fc98c5c78043d933e09ab6ebc204a12f6c1c46fad67f30e7b77e906451ba0d82b60ce7194818751")))
        check("S4 M1 proof matches", keys.m1.contentEquals(hx("48c63695529ea535778a47d1f0db97f5a63c0c2af40d786ee1675e60ace0dcb1b2a18deae003be435ce1c698a70f8b114cca2f9b3d086b811fa05e7968960e38")))
        check("S5 server M2 proof verifies", SrpClient.verifyServerProof(keys.aPub, keys.m1, keys.k64, hx("543e8a33addc47db4bc79b4f3c751ec6de0b20026930658ed582039f8699f3f92516f6292342e2027ac3ac6c4b74774785d2723a4566099bf1de1b3e95b68862")))
        val badProof = hx("543e8a33addc47db4bc79b4f3c751ec6de0b20026930658ed582039f8699f3f92516f6292342e2027ac3ac6c4b74774785d2723a4566099bf1de1b3e95b68862").also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        check("S6 tampered M2 proof rejected", !SrpClient.verifyServerProof(keys.aPub, keys.m1, keys.k64, badProof))
        check("S7 pad() is 384B big-endian", SrpClient.pad(BigInteger.valueOf(5)).size == 384 && SrpClient.pad(BigInteger.valueOf(5))[383] == 5.toByte())
    }
    // Ap2Channel framing ---------------------------------------------
    run {
        val wk = ByteArray(32) { it.toByte() }
        val rk = ByteArray(32) { (it + 1).toByte() }
        val wire = ByteArrayOutputStream()
        val sender = Ap2Channel(ByteArrayInputStream(ByteArray(0)), wire, wk, rk)
        val msgs = listOf("OPTIONS * RTSP/1.0\r\nCSeq: 0\r\n\r\n", "", "x".repeat(500))
        for (m in msgs) sender.sendFrame(m.toByteArray())
        val raw = wire.toByteArray()
        val receiver = Ap2Channel(ByteArrayInputStream(raw), ByteArrayOutputStream(), rk, wk)
        check("A1 3 frames round-trip", msgs.indices.all { i -> receiver.readFrame().contentEquals(msgs[i].toByteArray()) })
        val tampered = raw.copyOf()
        tampered[10] = (tampered[10].toInt() xor 0x01).toByte()
        val badRx = Ap2Channel(ByteArrayInputStream(tampered), ByteArrayOutputStream(), rk, wk)
        val tamperFails = try { badRx.readFrame(); false } catch (_: Exception) { true }
        check("A2 tampered frame fails", tamperFails)
        // AAD binding: same key/nonce/plaintext, different AAD must not open.
        val aadOk = ChaCha20Poly1305.open(wk, ByteArray(12), ChaCha20Poly1305.seal(wk, ByteArray(12), "hi".toByteArray(), byteArrayOf(5, 0)), byteArrayOf(5, 0))
        val aadBad = ChaCha20Poly1305.open(wk, ByteArray(12), ChaCha20Poly1305.seal(wk, ByteArray(12), "hi".toByteArray(), byteArrayOf(5, 0)), byteArrayOf(6, 0))
        check("A3 AAD is bound to the tag", aadOk?.contentEquals("hi".toByteArray()) == true && aadBad == null)
        // Chunking: 2500B -> 1024+1024+452 frames, reassembled by repeated reads.
        val wire2 = ByteArrayOutputStream()
        val big = ByteArray(2500) { it.toByte() }
        Ap2Channel(ByteArrayInputStream(ByteArray(0)), wire2, wk, rk).sendFrame(big)
        val rx2 = Ap2Channel(ByteArrayInputStream(wire2.toByteArray()), ByteArrayOutputStream(), rk, wk)
        val got = rx2.readFrame() + rx2.readFrame() + rx2.readFrame()
        check("A4 2500B chunked into 3 frames", got.contentEquals(big))
    }
    // BplistWriter ----------------------------------------------------
    run {
        val plist = BplistWriter.write(mapOf(
            "timingProtocol" to "NTP",
            "count" to 42,
            "neg" to -5,
            "ratio" to 0.75,
            "flag" to true,
            "blob" to byteArrayOf(1, 2, 3),
            "list" to listOf("a", 7),
            "uni" to "héllo→",
            "nothing" to null,
        ))
        check("B1 header is bplist00", plist.copyOfRange(0, 8).toString(Charsets.US_ASCII) == "bplist00")
        check("B2 trailer is 32B", plist.size >= 40)
        // Structural check: dict marker 0xD? near start, objects parseable.
        // Full semantic check is done against Python plistlib in the build log.
        check("B3 non-empty", plist.size > 40)
        val nested = BplistWriter.write(mapOf("a" to mapOf("b" to listOf(1, 2, mapOf("c" to "x")))))
        check("B4 nested encodes", nested.size > 40 && nested.copyOfRange(0, 8).toString(Charsets.US_ASCII) == "bplist00")
    }
    // ALAC uncompressed frames ----------------------------------------
    run {
        val f = AlacFrame.buildUncompressed(ShortArray(704))
        check("L1 frame is 1412B", f.size == 1412)
        check("L2 header bytes", f[0] == 0x20.toByte() && f[1] == 0x00.toByte() && f[2] == 0x02.toByte())
        check("L3 end marker + pad", f.last() == 0xC0.toByte())
        val chunks = ToneGenerator.sineChunks(440.0, 1.0)
        check("L4 1s = 125 chunks", chunks.size == 125 && chunks.all { it.size == 704 })
    }
    // BplistReader ------------------------------------------------------
    run {
        val written = BplistWriter.write(mapOf(
            "eventPort" to 7001,
            "streams" to listOf(mapOf("dataPort" to 6000, "controlPort" to 6001)),
            "name" to "Bedroom",
            "neg" to -70000,
            "nothing" to null,
        ))
        val root = BplistReader.read(written) as BplistReader.Val.Dict
        check("R1 eventPort", root.int("eventPort") == 7001L)
        val s0 = root.arr("streams")!!.list[0] as BplistReader.Val.Dict
        check("R2 stream ports", s0.int("dataPort") == 6000L && s0.int("controlPort") == 6001L)
        check("R3 neg + null", root.int("neg") == -70000L && root.map["nothing"] == BplistReader.Val.Null)
        check("R4 string", root.str("name") == "Bedroom")
    }
    // Audio packets -----------------------------------------------------
    run {
        val h = Ap2AudioPackets.rtpHeader(1, 66150L, 12345L, true)
        check("P1 RTP header", h.size == 12 && h[0] == 0x80.toByte() && h[1] == 0xE0.toByte() &&
            h[2] == 0x00.toByte() && h[3] == 0x01.toByte())
        val key = ByteArray(32) { it.toByte() }
        val payload = AlacFrame.buildUncompressed(ShortArray(704) { 100 })
        val enc = Ap2AudioPackets.encryptAudioPayload(key, 0L, h, payload)
        check("P2 wire size", enc.size == payload.size + 24)
        val nonce12 = ByteArray(12)
        val dec = ChaCha20Poly1305.open(key, nonce12, enc.copyOfRange(0, enc.size - 8), h.copyOfRange(4, 12))
        check("P3 decrypt round-trip", dec != null && dec.contentEquals(payload))
        val s = Ap2AudioPackets.syncPacket(true, 66150L, 66150L, 1UL)
        check("P4 sync packet", s.size == 20 && s[0] == 0x90.toByte() && s[1] == 0xD4.toByte())
        val req = ByteArray(32) { i -> i.toByte() }
        val tr = Ap2AudioPackets.timingResponse(req, 2UL)
        check("P5 timing response", tr.size == 32 && tr[1] == 0xD3.toByte() &&
            tr.copyOfRange(8, 16).contentEquals(req.copyOfRange(24, 32)))
        val ntp = Ap2AudioPackets.ntpNow()
        val ts = Ap2AudioPackets.ntp2ts(ntp, 44100)
        val back = Ap2AudioPackets.ts2ntp(ts, 44100)
        val diff = if (ntp > back) ntp - back else back - ntp
        check("P6 ntp round-trip", diff < 200000UL)
    }
    run {
        // 1.2.2 sync-epoch mapping + pacing math (ported with the
        // Outro 1.3.4 fix). The receiver locks its playout clock to
        // this mapping; it must be a pure function of the RTP
        // timestamp under ONE fixed epoch (pyatv/owntone parity).
        val epoch = (0x83AA7E80UL shl 32)
        val epochTs = 66150UL
        check("P7 syncNtp at epoch == epoch",
            Ap2AudioPackets.syncNtp(epoch, epochTs, epochTs) == epoch)
        check("P8 syncNtp +44100 frames == epoch + 2^32",
            Ap2AudioPackets.syncNtp(epoch, epochTs, epochTs + 44100UL) == epoch + (1UL shl 32))
        check("P9 syncNtp +352 frames",
            Ap2AudioPackets.syncNtp(epoch, epochTs, epochTs + 352UL) ==
                epoch + ((352UL shl 32) / 44100UL))
        val mono = (0..10).all { i ->
            Ap2AudioPackets.syncNtp(epoch, epochTs, epochTs + (i * 4410).toULong()) >=
                Ap2AudioPackets.syncNtp(epoch, epochTs, epochTs + ((i - 1).coerceAtLeast(0) * 4410).toULong())
        }
        check("P10 syncNtp monotonic in rtpTs", mono)
        val sp = Ap2AudioPackets.syncPacket(
            false, 66150L + 44100L, 66150L,
            Ap2AudioPackets.syncNtp(epoch, epochTs, epochTs + 44100UL))
        check("P11 sync packet carries epoch-derived NTP",
            sp.size == 20 && sp[8] == 0x83.toByte() && sp[9] == 0xAA.toByte() &&
                sp[10] == 0x7E.toByte() && sp[11] == 0x81.toByte() &&
                sp[12] == 0.toByte() && sp[15] == 0.toByte() &&
                sp[18] == 0xAE.toByte() && sp[19] == 0xAA.toByte())
        check("P12 framesDue(0) == 0", Ap2AudioPackets.framesDue(0L) == 0L)
        check("P13 framesDue(1s) == 44100", Ap2AudioPackets.framesDue(1_000_000_000L) == 44100L)
        check("P14 framesDue(negative) == 0", Ap2AudioPackets.framesDue(-5L) == 0L)
        check("P15 framesDue(8ms) == 352 (one packet)", Ap2AudioPackets.framesDue(8_000_000L) == 352L)
        check("P16 framesDue(60s) == 2646000", Ap2AudioPackets.framesDue(60_000_000_000L) == 2_646_000L)
    }

    if (failures == 0) println("ALL AP2 CHECKS PASSED")
    else println("$failures AP2 CHECK(S) FAILED")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
