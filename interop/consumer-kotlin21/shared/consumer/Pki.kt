package consumer

// A self-signed P-256 certificate for localhost (SAN DNS:localhost, IP:127.0.0.1), valid until 2125; tests only.
const val CERTIFICATE = """
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

const val KEY = """
-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgxdQDKpOe7dh4VzxW
5QgRwZxaCqB0Hmjg5DLTv4YFZ6ihRANCAAREcrIqlX250aqRcnA9L6zTi87ChZ1b
yp0B/vPgn6zsikmM2QkMmFfzDi3OOv1/uQogxqjp3MAYhNyfGx+uWorO
-----END PRIVATE KEY-----
"""

/** The payload sizes every consumer echoes. */
val SIZES = listOf(0, 1, 1200, 70_000, 1_000_000)

/** Prints the verdict and exits with it. */
fun finish(name: String, failures: Int): Nothing {
    println("$name: ${if (failures == 0) "PASS" else "FAIL ($failures)"}")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
