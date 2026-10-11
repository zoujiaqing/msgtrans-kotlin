package msgtrans.transport

import neton.quic.testkit.TestCa

internal actual fun quicTestPki(): QuicTestPki {
    val ca = TestCa.create("msgtrans quic test CA")
    val identity = ca.issue("localhost", dnsNames = listOf("localhost"), ipAddresses = listOf("127.0.0.1"))
    return QuicTestPki(identity.chainWith(ca.identity), identity.privateKey, ca.trustAnchors, TestCa.create("unrelated CA").trustAnchors)
}
