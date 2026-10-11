package msgtrans.transport

import neton.quic.testkit.TestCa

internal actual fun wssTestPki(): WssTestPki {
    val ca = TestCa.create("msgtrans websocket test CA")
    val identity = ca.issue("localhost", dnsNames = listOf("localhost"), ipAddresses = listOf("127.0.0.1"))
    return WssTestPki(identity.certPem, identity.keyPem, ca.identity.certPem)
}
