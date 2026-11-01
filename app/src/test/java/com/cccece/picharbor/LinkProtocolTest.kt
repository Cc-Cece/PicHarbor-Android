package com.cccece.picharbor

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class LinkProtocolTest {
    private val fingerprint="ab".repeat(32)

    @Test fun pairMacMatchesKnownVector() {
        assertEquals("0166d80f4a4166b72ae92eeaf127c91ccc9268382b3fc5099e720486f9d4a062",
            LinkProtocol.pairMac("123456","device-1","aabbccddeeff0011",fingerprint))
    }

    @Test fun pinAcceptsFullMatchOrSixteenHexPrefix() {
        assertTrue(LinkProtocol.pinMatches(fingerprint,fingerprint))
        assertTrue(LinkProtocol.pinMatches(fingerprint.uppercase(),"AB".repeat(8)))
        assertFalse(LinkProtocol.pinMatches(fingerprint,"ab".repeat(8)+"00"))
        assertFalse(LinkProtocol.pinMatches(fingerprint,"zz".repeat(8)))
        assertFalse(LinkProtocol.pinMatches("abc",fingerprint))
    }

    @Test fun qrAndManualEntry() {
        val qr=LinkProtocol.parseQr("picharbor://link?v=1&host=192.168.1.5&port=8443&fp=$fingerprint&code=123456")
        assertEquals("192.168.1.5",qr!!.host)
        assertEquals(8443,qr.port)
        assertEquals(fingerprint,qr.pin)
        assertEquals("123456",qr.code)
        assertTrue(qr.alts.isEmpty())
        val withAlts=LinkProtocol.parseQr("picharbor://link?v=1&host=192.168.1.5&port=8443&fp=$fingerprint&code=123456&alts=10.0.0.8,192.168.1.5,127.0.0.1")
        assertEquals(listOf("10.0.0.8"),withAlts!!.alts)
        assertNull(LinkProtocol.parseQr("https://example.com"))
        val manual=LinkProtocol.manual("192.168.1.5","8443",fingerprint.take(16),"123456")
        assertEquals(fingerprint.take(16),manual!!.pin)
        assertNull(LinkProtocol.manual("192.168.1.5","8443",fingerprint,"123456"))
    }

    @Test fun beaconJson() {
        val raw="""{"v":1,"name":"Desk","host":"10.0.0.8","port":47877,"fp":"$fingerprint"}""".toByteArray()
        val found=LinkProtocol.parseBeacon(raw)
        assertEquals("Desk",found!!.name)
        assertEquals(47877,found.port)
        assertEquals(listOf("10.0.0.8"),found.hosts)
        val many=LinkProtocol.parseBeacon("""{"v":1,"name":"Desk","host":"10.0.0.8","hosts":["192.168.1.9","10.0.0.8","127.0.0.1"],"port":47877,"fp":"$fingerprint"}""".toByteArray())
        assertEquals(listOf("10.0.0.8","192.168.1.9"),many!!.hosts)
        assertEquals(listOf("192.168.1.9","10.0.0.8"),LinkProtocol.orderAddresses(many.hosts,"192.168.1.20"))
        assertNull(LinkProtocol.parseBeacon("""{"v":2,"name":"Desk","host":"10.0.0.8","port":1,"fp":"$fingerprint"}""".toByteArray()))
    }

    @Test fun archiveCodeRulesMatchTheComputer() {
        assertTrue(LinkProtocol.usableArchive("PH-7K2M9Q"))
        assertTrue(LinkProtocol.usableArchive("ABCDEFGH1234"))
        assertFalse(LinkProtocol.usableArchive("android:PH-7K2M9Q"))
        assertFalse(LinkProtocol.usableArchive("aaaa"))
        assertFalse(LinkProtocol.usableArchive("n/a"))
        assertFalse(LinkProtocol.usableArchive("bad&id"))
    }

    @Test fun blobIdIsShortHexNotTheContentUri() {
        val uri="content://media/external/images/media/42"
        val id=LinkProtocol.blobId(uri)
        assertTrue(LinkProtocol.ID.matches(id))
        assertEquals(32,id.length)
        assertFalse(id.contains(":")||id.contains("/"))
    }

    @Test fun motionSampleStaysOneComputerItem() {
        assertEquals(1,LinkProtocol.originalCount("prefix MotionPhoto suffix".toByteArray()))
        assertEquals(1,LinkProtocol.originalCount("plain jpeg".toByteArray()))
    }

    @Test fun signatureIsRaw64Bytes() {
        val generator=KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair=generator.generateKeyPair()
        val point=LinkProtocol.publicPoint(pair.public as ECPublicKey)
        assertEquals(65,point.size)
        assertEquals(4,point[0].toInt() and 0xff)
        val message=LinkProtocol.sessionMessage("aabbccddeeff0011",fingerprint)
        val signer=Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private)
        signer.update(message)
        val raw=LinkProtocol.derToP1363(signer.sign())
        assertEquals(64,raw.size)
        val verifier=Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(pair.public)
        verifier.update(message)
        assertTrue(verifier.verify(LinkProtocol.p1363ToDer(raw)))
    }
}

class LivePairingTest {
    private fun file(id:String,name:String,folder:String="camera")=LivePairing.StemFile(id,name,folder)

    @Test fun oneStillAndOneVideoPair() {
        val matches=LivePairing.match(listOf(file("p","IMG_1.jpg"),file("v","IMG_1.MOV"),file("other","IMG_2.jpg")))
        assertEquals(1,matches.size)
        assertEquals("p",matches[0].photoId)
        assertEquals("v",matches[0].videoId)
    }

    @Test fun duplicateExtensionsDoNotPair() {
        assertTrue(LivePairing.match(listOf(file("a","IMG.jpg"),file("b","IMG.heic"),file("v","IMG.mov"))).isEmpty())
        assertTrue(LivePairing.match(listOf(file("p","IMG.jpg"),file("a","IMG.mov"),file("b","IMG.mp4"))).isEmpty())
        assertTrue(LivePairing.match(listOf(file("a","IMG.jpg"),file("b","IMG.jpeg"),file("v","IMG.mov"))).isEmpty())
    }

    @Test fun otherFoldersAndPngDoNotBlock() {
        val matches=LivePairing.match(listOf(
            file("p","Trip.heif","sd"),file("v","Trip.mp4","sd"),
            file("x","Trip.jpg","camera"),file("png","Trip.png","sd")))
        assertEquals(listOf("p"),matches.map{it.photoId})
        assertEquals(listOf("v"),matches.map{it.videoId})
    }

    @Test fun motionMarkerIsDetectedWithoutSplitting() {
        assertFalse(LivePairing.containsMotion("jpeg".toByteArray()))
        assertTrue(LivePairing.containsMotion("abcMotionPhotodef".toByteArray()))
    }
}
