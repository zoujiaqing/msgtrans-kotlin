package msgtrans.transport

import neton.quic.proto.Certificates
import neton.quic.proto.PrivateKey

/**
 * Fixed certificates (the JVM cannot generate them): a self-signed P-256 certificate for localhost (SAN DNS:localhost,
 * IP:127.0.0.1) that the client trusts directly, and an unrelated self-signed one; valid until 2125, made once with the
 * OpenSSL command line (2026-10-11). Tests only.
 */
internal actual fun quicTestPki(): QuicTestPki {
    val server = Certificates.pem(SERVER)
    return QuicTestPki(server, PrivateKey.pem(KEY), server, Certificates.pem(UNRELATED))
}

private const val SERVER = """
-----BEGIN CERTIFICATE-----
MIIBmzCCAUGgAwIBAgIULG2s9UnTRxtCMyvJpHmJ9RJOlIUwCgYIKoZIzj0EAwIw
FDESMBAGA1UEAwwJbG9jYWxob3N0MCAXDTI2MDEwMTAwMDAwMFoYDzIxMjUxMjMx
MDAwMDAwWjAUMRIwEAYDVQQDDAlsb2NhbGhvc3QwWTATBgcqhkjOPQIBBggqhkjO
PQMBBwNCAAREcrIqlX250aqRcnA9L6zTi87ChZ1byp0B/vPgn6zsikmM2QkMmFfz
Di3OOv1/uQogxqjp3MAYhNyfGx+uWorOo28wbTAdBgNVHQ4EFgQUeqT0AcLE56Vs
T6miR1Tsn452PJUwHwYDVR0jBBgwFoAUeqT0AcLE56VsT6miR1Tsn452PJUwDwYD
VR0TAQH/BAUwAwEB/zAaBgNVHREEEzARgglsb2NhbGhvc3SHBH8AAAEwCgYIKoZI
zj0EAwIDSAAwRQIgE8UptQolcbP+rsJvP2Ip2M4D6g31ddqnoXD+eHHzxJECIQCo
BPvyRrFNxICEsrPluRCn72kLejlWCI0CfWjrTYZ9JA==
-----END CERTIFICATE-----
"""

private const val KEY = """
-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgxdQDKpOe7dh4VzxW
5QgRwZxaCqB0Hmjg5DLTv4YFZ6ihRANCAAREcrIqlX250aqRcnA9L6zTi87ChZ1b
yp0B/vPgn6zsikmM2QkMmFfzDi3OOv1/uQogxqjp3MAYhNyfGx+uWorO
-----END PRIVATE KEY-----
"""

private const val UNRELATED = """
-----BEGIN CERTIFICATE-----
MIIBkjCCATmgAwIBAgIUF3qzyxJ3NuIA3X/DXwUFiZf5wgUwCgYIKoZIzj0EAwIw
EzERMA8GA1UEAwwIc3RyYW5nZXIwIBcNMjYwMTAxMDAwMDAwWhgPMjEyNTEyMzEw
MDAwMDBaMBMxETAPBgNVBAMMCHN0cmFuZ2VyMFkwEwYHKoZIzj0CAQYIKoZIzj0D
AQcDQgAEYmjxKcAd9Wida0a0pmfYpDAr882kihINkQQITE7ZCKjsXS4WVY0JFB4w
D5Ryjo2pJ/n45lmfZcGojK54IMSNQaNpMGcwHQYDVR0OBBYEFNUQ9hdTj+fpjJoA
liWLEQXw1IfSMB8GA1UdIwQYMBaAFNUQ9hdTj+fpjJoAliWLEQXw1IfSMA8GA1Ud
EwEB/wQFMAMBAf8wFAYDVR0RBA0wC4IJbG9jYWxob3N0MAoGCCqGSM49BAMCA0cA
MEQCIBQWfdzYx7Y0g6Nk9O09QYLXAOsoApAWz/NeD/0J6IE4AiBq0Jjfsp0EjSAb
KTzeNlgE/AUS7f0sux6diWopcjk5HA==
-----END CERTIFICATE-----
"""
