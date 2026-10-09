//! msgtrans-rust QUIC and WebSocket peer for the Kotlin interop runs (see ../run-quic-interop.sh, ../run-websocket-interop.sh).
//!
//!   peer server <addr> <cert.pem> <key.pem> <sessions>   QUIC: serve <sessions> sessions, then exit
//!   peer client <addr> <server-name> <ca.pem>           QUIC: run the checklist against a server, exit 0 if all pass
//!   peer ws-server <addr> <sessions>                    WebSocket (ws://, path /): serve <sessions> sessions
//!   peer ws-client <url> [ca.pem]                       WebSocket: the checklist; wss:// trusts ca.pem
//!
//! The scenario both implementations follow:
//! - a Request is answered with its payload (any biz_type but 200);
//! - a OneWay is echoed back as a OneWay with the same biz_type;
//! - a Request with biz_type 200 makes the server send a Request ("server-asks") back to the client, and the answer is
//!   "client said: <the client's response>".
//! Every line meant for the run's summary starts with "[interop]".

use async_trait::async_trait;
use bytes::Bytes;
use msgtrans::{
    ClientEvent, ClientTls, CompressionType, ConnectionInfo, Packet, QuicClientConfig, QuicServerConfig, RequestOptions,
    Responder, SendOptions, SessionHandler, SessionId, SessionSender, TransportClientBuilder, TransportServer,
    TransportServerBuilder, WebSocketClientConfig, WebSocketServerConfig,
};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, OnceLock};
use std::time::Duration;
use tokio::sync::{mpsc, Notify};

const BIZ_ASK_BACK: u8 = 200;
const BIZ_ONE_WAY: u8 = 42;

fn pattern(size: usize) -> Vec<u8> {
    (0..size).map(|i| ((i * 31 + 7) % 251) as u8).collect()
}

struct Server {
    handle: OnceLock<TransportServer>,
    closed: AtomicUsize,
    sessions: usize,
    done: Notify,
}

#[async_trait]
impl SessionHandler for Server {
    async fn on_connected(&self, session_id: SessionId, info: ConnectionInfo) {
        println!("[interop] rust server: session {session_id} from {}", info.peer_addr);
    }

    async fn on_message(&self, _session_id: SessionId, packet: Packet, sender: SessionSender) {
        let biz = packet.biz_type();
        if let Err(e) = sender.send_data_with_options(packet.into_payload(), SendOptions::new().biz_type(biz)).await {
            println!("[interop] rust server: one-way echo failed: {e:?}");
        }
    }

    async fn on_request(&self, session_id: SessionId, request: Packet, responder: Responder) {
        if request.biz_type() != BIZ_ASK_BACK {
            let _ = responder.respond(request.into_payload()).await;
            return;
        }
        // The reverse request runs beside the handler, so the session keeps reading while it waits.
        let Some(server) = self.handle.get().cloned() else { return };
        tokio::spawn(async move {
            let answer = match server.request(session_id, b"server-asks").await {
                Ok(bytes) => format!("client said: {}", String::from_utf8_lossy(&bytes)),
                Err(e) => format!("reverse request failed: {e:?}"),
            };
            let _ = responder.respond(answer.into_bytes()).await;
        });
    }

    async fn on_disconnected(&self, session_id: SessionId, reason: msgtrans::CloseReason) {
        println!("[interop] rust server: session {session_id} closed ({reason:?})");
        if self.closed.fetch_add(1, Ordering::SeqCst) + 1 >= self.sessions {
            self.done.notify_one();
        }
    }
}

type Error = Box<dyn std::error::Error>;

async fn quic_server(addr: &str, cert: &str, key: &str, sessions: usize) -> Result<(), Error> {
    let config = QuicServerConfig::new(addr)?
        .cert_pem(std::fs::read_to_string(cert)?)
        .key_pem(std::fs::read_to_string(key)?);
    server(TransportServerBuilder::new().protocol(config), addr, sessions).await
}

async fn ws_server(addr: &str, sessions: usize) -> Result<(), Error> {
    server(TransportServerBuilder::new().protocol(WebSocketServerConfig::new(addr)?), addr, sessions).await
}

async fn server(builder: TransportServerBuilder, addr: &str, sessions: usize) -> Result<(), Error> {
    let handler = Arc::new(Server { handle: OnceLock::new(), closed: AtomicUsize::new(0), sessions, done: Notify::new() });
    let transport = builder.build(handler.clone()).await?;
    let _ = handler.handle.set(transport.clone());
    let serving = tokio::spawn(async move { transport.serve().await });
    println!("[interop] rust server: listening on {addr}");
    handler.done.notified().await;
    println!("[interop] rust server: {sessions} session(s) served");
    serving.abort();
    Ok(())
}

async fn quic_client(addr: &str, server_name: &str, ca: &str) -> Result<(), Error> {
    let config = QuicClientConfig::new(addr)?
        .server_name(server_name)
        .ca_cert_pem(std::fs::read_to_string(ca)?)
        .connect_timeout(Duration::from_secs(10));
    client(TransportClientBuilder::new().protocol(config), addr).await
}

async fn ws_client(url: &str, ca: Option<&str>) -> Result<(), Error> {
    let mut config = WebSocketClientConfig::new(url)?.connect_timeout(Duration::from_secs(10));
    if let Some(ca) = ca {
        config = config.tls(ClientTls::CustomCa(std::fs::read_to_string(ca)?));
    }
    client(TransportClientBuilder::new().protocol(config), url).await
}

async fn client(builder: TransportClientBuilder, addr: &str) -> Result<(), Error> {
    let mut transport = builder.build().await?;
    if let Err(e) = transport.connect().await {
        println!("[interop] rust client: connect failed: {e:?}");
        return Err(e.into());
    }
    println!("[interop] rust client: connected to {addr}");

    // Answer the server's requests; hand one-way messages to the checklist.
    let mut events = transport.events().await?;
    let (one_way_tx, mut one_way_rx) = mpsc::unbounded_channel();
    tokio::spawn(async move {
        while let Some(event) = events.next().await {
            match event {
                ClientEvent::Request(req) => req.respond_detached(b"rust-client-answer".to_vec()),
                ClientEvent::Message(msg) => {
                    let _ = one_way_tx.send((msg.biz_type(), msg.into_payload()));
                }
                ClientEvent::Disconnected { .. } => break,
                _ => {}
            }
        }
    });

    let mut failures = 0;
    let mut check = |ok: bool, what: String| {
        if ok { println!("[interop] ok: {what}") } else { println!("[interop] FAIL: {what}"); failures += 1 }
    };

    for size in [0usize, 1, 1200, 70_000, 1_000_000, 3_000_000] {
        let payload = pattern(size);
        let reply = transport.request_with_options(Bytes::from(payload.clone()), RequestOptions::new().biz_type(1)).await;
        check(matches!(&reply, Ok(r) if r[..] == payload[..]), format!("request echo, {size} bytes"));
    }
    let text = "msgtrans interop ".repeat(5000).into_bytes();
    for compression in [CompressionType::Zstd, CompressionType::Zlib] {
        let reply = transport
            .request_with_options(Bytes::from(text.clone()), RequestOptions::new().biz_type(2).compression(compression))
            .await;
        check(matches!(&reply, Ok(r) if r[..] == text[..]), format!("compressed request echo, {compression:?}"));
    }
    let concurrent = concurrent_requests(&transport, 64).await;
    check(concurrent, "64 concurrent requests".to_string());

    let sent = transport
        .send_with_options(Bytes::from_static(b"one-way"), SendOptions::new().biz_type(BIZ_ONE_WAY))
        .await;
    let echoed = tokio::time::timeout(Duration::from_secs(10), one_way_rx.recv()).await;
    check(
        sent.is_ok() && matches!(&echoed, Ok(Some((biz, p))) if *biz == BIZ_ONE_WAY && &p[..] == b"one-way"),
        "one-way echoed as one-way".to_string(),
    );

    let asked = transport.request_with_options(Bytes::from_static(b"ask"), RequestOptions::new().biz_type(BIZ_ASK_BACK)).await;
    check(
        matches!(&asked, Ok(r) if &r[..] == b"client said: rust-client-answer"),
        format!("server's reverse request answered by the client ({asked:?})"),
    );

    let _ = transport.disconnect().await;
    // quinn sends the CONNECTION_CLOSE from its endpoint driver: give it a moment before the process exits, or the
    // server only learns of the end from its idle timeout.
    tokio::time::sleep(Duration::from_millis(300)).await;
    if failures == 0 {
        println!("[interop] rust client: all checks passed");
        Ok(())
    } else {
        Err(format!("{failures} check(s) failed").into())
    }
}

async fn concurrent_requests(transport: &msgtrans::TransportClient, n: usize) -> bool {
    let requests = (0..n).map(|i| async move {
        let payload = format!("c{i}").into_bytes();
        matches!(transport.request(&payload).await, Ok(r) if r[..] == payload[..])
    });
    futures::future::join_all(requests).await.into_iter().all(|ok| ok)
}

#[tokio::main]
async fn main() {
    let args: Vec<String> = std::env::args().collect();
    let result = match args.get(1).map(String::as_str) {
        Some("server") if args.len() == 6 => {
            quic_server(&args[2], &args[3], &args[4], args[5].parse().expect("sessions")).await
        }
        Some("client") if args.len() == 5 => quic_client(&args[2], &args[3], &args[4]).await,
        Some("ws-server") if args.len() == 4 => ws_server(&args[2], args[3].parse().expect("sessions")).await,
        Some("ws-client") if args.len() == 3 || args.len() == 4 => ws_client(&args[2], args.get(3).map(String::as_str)).await,
        _ => {
            eprintln!("usage: see the header of main.rs");
            std::process::exit(2);
        }
    };
    if let Err(e) = result {
        println!("[interop] error: {e}");
        std::process::exit(1);
    }
}
