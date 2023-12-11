/*
 * Copyright (c) 2011-2019 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package io.vertx.core.http;

import io.vertx.core.Future;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.impl.HttpClientImpl;
import io.vertx.core.net.ProxyOptions;
import io.vertx.core.net.ProxyType;
import io.vertx.core.net.SocketAddress;
import io.vertx.test.proxy.HttpProxy;
import io.vertx.test.proxy.SocksProxy;
import io.vertx.test.proxy.TestProxyBase;
import io.vertx.test.tls.Cert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class Http1xProxyTest extends HttpTestBase {

  @Override
  protected VertxOptions getOptions() {
    VertxOptions options = super.getOptions();
    options.getAddressResolverOptions().setHostsValue(Buffer.buffer("" +
      "127.0.0.1 localhost\n" +
      "127.0.0.1 www1.example1.com\n" +
      "127.0.0.1 www2.example1.com\n" +
      "127.0.0.1 www1.example2.com\n" +
      "127.0.0.1 www2.example2.com\n"
      ));
    return options;
  }

  @Test
  public void testHttpProxyRequest() throws Exception {
    startProxy(null, ProxyType.HTTP);
    client.close();
    client = vertx.createHttpClient(new HttpClientOptions()
      .setProxyOptions(new ProxyOptions().setType(ProxyType.HTTP).setHost("localhost").setPort(proxy.port())));
    testHttpProxyRequest(() -> client.request(new RequestOptions()
      .setHost(DEFAULT_HTTP_HOST)
      .setPort(DEFAULT_HTTP_PORT)
      .setURI("/")
    ).compose(HttpClientRequest::send)).onComplete(onSuccess(v -> {
      assertProxiedRequest(DEFAULT_HTTP_HOST);
      testComplete();
    }));
    await();
  }

  @Test
  public void testHttpProxyRequest2() throws Exception {
    startProxy(null, ProxyType.HTTP);
    testHttpProxyRequest(() -> client.request(new RequestOptions()
      .setProxyOptions(new ProxyOptions().setType(ProxyType.HTTP).setHost("localhost").setPort(proxy.port()))
      .setHost(DEFAULT_HTTP_HOST)
      .setPort(DEFAULT_HTTP_PORT)
      .setURI("/")
    ).compose(HttpClientRequest::send)).onComplete(onSuccess(v -> {
      assertProxiedRequest(DEFAULT_HTTP_HOST);
      testComplete();
    }));
    await();
  }

  @Test
  public void testAcceptFilter() throws Exception {
    testFilter(true);
  }

  @Test
  public void testRejectFilter() throws Exception {
    testFilter(false);
  }

  private void testFilter(boolean accept) throws Exception {
    startProxy(null, ProxyType.HTTP);
    client.close();
    client = vertx.createHttpClient(new HttpClientOptions()
      .setProxyOptions(new ProxyOptions().setType(ProxyType.HTTP).setHost("localhost").setPort(proxy.port())));
    Set<SocketAddress> filtered = Collections.synchronizedSet(new HashSet<>());
    ((HttpClientImpl)client).proxyFilter(so -> {
      filtered.add(so);
      return accept;
    });
    testHttpProxyRequest(() -> client.request(new RequestOptions()
      .setHost(DEFAULT_HTTP_HOST)
      .setPort(DEFAULT_HTTP_PORT)
      .setURI("/")
    ).compose(HttpClientRequest::send)).onComplete(onSuccess(v -> {
      if (accept) {
        assertProxiedRequest(DEFAULT_HTTP_HOST);
      }
      assertEquals(Collections.singleton(SocketAddress.inetSocketAddress(DEFAULT_HTTP_PORT, "localhost")), filtered);
      testComplete();
    }));
    await();
  }

  @Test
  public void testNonProxyHosts1() throws Exception {
    testNonProxyHosts(Collections.singletonList("www1.example1.com"), "www1.example1.com", false);
  }

  @Test
  public void testNonProxyHosts2() throws Exception {
    testNonProxyHosts(Collections.singletonList("www1.example1.com"), "www2.example1.com", true);
  }

  @Test
  public void testNonProxyHosts3() throws Exception {
    testNonProxyHosts(Collections.singletonList("*.example2.com"), "www1.example2.com", false);
  }

  @Test
  public void testNonProxyHosts4() throws Exception {
    testNonProxyHosts(Collections.singletonList("*.example2.com"), "www2.example2.com", false);
  }

  private void testNonProxyHosts(List<String> nonProxyHosts, String host, boolean proxied) throws Exception {
    startProxy(null, ProxyType.HTTP);
    client.close();
    client = vertx.createHttpClient(new HttpClientOptions()
      .setNonProxyHosts(nonProxyHosts)
      .setProxyOptions(new ProxyOptions().setType(ProxyType.HTTP).setHost("localhost").setPort(proxy.port())));
    testHttpProxyRequest(() -> client.request(new RequestOptions()
      .setHost(host)
      .setPort(DEFAULT_HTTP_PORT)
      .setURI("/")
    ).compose(HttpClientRequest::send)).onComplete(onSuccess(v -> {
      if (proxied) {
        assertProxiedRequest(host);
      }
      testComplete();
    }));
    await();
  }

  @Test
  public void testHttpProxyRequestOverrideClientSsl() throws Exception {
    startProxy(null, ProxyType.HTTP);
    client.close();
    client = vertx.createHttpClient(new HttpClientOptions()
      .setSsl(true).setProxyOptions(new ProxyOptions().setType(ProxyType.HTTP).setHost("localhost").setPort(proxy.port())));
    testHttpProxyRequest(() -> client
      .request(new RequestOptions().setSsl(false).setHost("localhost").setPort(DEFAULT_HTTP_PORT))
      .compose(HttpClientRequest::send)).onComplete(onSuccess(v -> {
      assertProxiedRequest(DEFAULT_HTTP_HOST);
      testComplete();
    }));
    await();
  }

  private void assertProxiedRequest(String host) {
    assertNotNull("request did not go through proxy", proxy.getLastUri());
    assertEquals("Host header doesn't contain target host", host + ":" + DEFAULT_HTTP_PORT, proxy.getLastRequestHeaders().get("Host"));
  }

  private Future<Void> testHttpProxyRequest(Supplier<Future<HttpClientResponse>> reqFact) throws Exception {
    server.requestHandler(req -> {
      req.response().end();
    });

    return server.listen().compose(s -> {
      return reqFact.get().compose(resp -> {
        int sc = resp.statusCode();
        if (sc == 200) {
          return Future.succeededFuture();
        } else {
          return Future.failedFuture("Was expected 200 response instead of " + sc);
        }
      });
    });
  }

  @Test
  public void testHttpProxyRequestAuth() throws Exception {
    startProxy("user", ProxyType.HTTP);

    client.close();
    client = vertx.createHttpClient(new HttpClientOptions()
      .setProxyOptions(new ProxyOptions().setType(ProxyType.HTTP).setHost("localhost").setPort(proxy.port())
        .setUsername("user").setPassword("user")));

    server.requestHandler(req -> {
      req.response().end();
    });

    server.listen(onSuccess(s -> {
      client.request(new RequestOptions()
        .setHost(DEFAULT_HTTP_HOST)
        .setPort(DEFAULT_HTTP_PORT)
        .setURI("/")
      ).onComplete(onSuccess(req -> {
        req.send(onSuccess(resp -> {
          assertEquals(200, resp.statusCode());
          assertNotNull("request did not go through proxy", proxy.getLastUri());
          assertEquals("Host header doesn't contain target host", DEFAULT_HTTP_HOST_AND_PORT, proxy.getLastRequestHeaders().get("Host"));
          testComplete();
        }));
      }));
    }));
    await();
  }

  @Test
  public void testHttpProxyFtpRequest() throws Exception {
    startProxy(null, ProxyType.HTTP);
    client.close();
    client = vertx.createHttpClient(new HttpClientOptions()
      .setProxyOptions(new ProxyOptions().setType(ProxyType.HTTP).setHost(DEFAULT_HTTP_HOST).setPort(proxy.port())));
    final String url = "ftp://ftp.gnu.org/gnu/";
    proxy.setForceUri("http://" + DEFAULT_HTTP_HOST_AND_PORT+ "/");
    server.requestHandler(req -> {
      req.response().end();
    });

    server.listen(onSuccess(s -> {
      client.request(new RequestOptions().setURI(url))
        .onComplete(onSuccess(req -> {
        req.send(onSuccess(resp -> {
          assertEquals(200, resp.statusCode());
          assertEquals("request did sent the expected url", url, proxy.getLastUri());
          testComplete();
        }));
      }));
    }));
    await();
  }

  @Test
  public void testHttpSocksProxyRequest() throws Exception {
    startProxy(null, ProxyType.SOCKS5);

    client.close();
    client = vertx.createHttpClient(new HttpClientOptions()
      .setProxyOptions(new ProxyOptions().setType(ProxyType.SOCKS5).setHost("localhost").setPort(proxy.port())));

    server.requestHandler(req -> req.response().end());

    startServer();

    client.request(new RequestOptions()
      .setHost(DEFAULT_HTTP_HOST)
      .setPort(DEFAULT_HTTP_PORT)
      .setURI("/")).onComplete(onSuccess(req -> {
      req.send(onSuccess(resp -> {
        assertEquals(200, resp.statusCode());
        assertNotNull("request did not go through proxy", proxy.getLastUri());
        testComplete();
      }));
    }));
    await();
  }

  @Test
  public void testHttpSocksProxyRequestAuth() throws Exception {
    startProxy("user", ProxyType.SOCKS5);

    client.close();
    client = vertx.createHttpClient(new HttpClientOptions()
      .setProxyOptions(new ProxyOptions().setType(ProxyType.SOCKS5).setHost("localhost").setPort(proxy.port())
        .setUsername("user").setPassword("user")));

    server.requestHandler(req -> {
      req.response().end();
    });

    startServer();

    client.request(new RequestOptions()
      .setHost(DEFAULT_HTTP_HOST)
      .setPort(DEFAULT_HTTP_PORT)
      .setURI("/")).onComplete(onSuccess(req -> {
      req.send(onSuccess(resp -> {
        assertEquals(200, resp.statusCode());
        assertNotNull("request did not go through proxy", proxy.getLastUri());
        testComplete();
      }));
    }));
    await();
  }

  @Test
  public void testHttpProxyPooling() throws Exception {
    HttpProxy proxy1 = new HttpProxy().port(HttpProxy.DEFAULT_PORT);
    HttpProxy proxy2 = new HttpProxy().port(HttpProxy.DEFAULT_PORT + 1);
    ProxyOptions req1 = new ProxyOptions()
      .setType(ProxyType.HTTP)
      .setHost("localhost")
      .setPort(proxy1.port());
    ProxyOptions req2 = new ProxyOptions()
      .setType(ProxyType.HTTP)
      .setHost("localhost")
      .setPort(proxy2.port());
    List<String> res = testPooling(req1, req2, proxy1, proxy2);
    assertEquals(Arrays.asList(proxy1.lastLocalAddress(), proxy2.lastLocalAddress()), res);
  }

  @Test
  public void testHttpProxyPooling2() throws Exception {
    HttpProxy proxy = new HttpProxy().port(HttpProxy.DEFAULT_PORT);
    ProxyOptions req = new ProxyOptions()
      .setType(ProxyType.HTTP)
      .setHost("localhost")
      .setPort(proxy.port());
    List<String> res = testPooling(req, req, proxy);
    assertEquals(Arrays.asList(proxy.lastLocalAddress(), proxy.lastLocalAddress()), res);
  }

  @Test
  public void testHttpProxyAuthPooling1() throws Exception {
    HttpProxy proxy = new HttpProxy().port(SocksProxy.DEFAULT_PORT).username(Arrays.asList("user1", "user2"));
    ProxyOptions req1 = new ProxyOptions()
      .setUsername("user1")
      .setPassword("user1")
      .setType(ProxyType.HTTP)
      .setHost("localhost")
      .setPort(proxy.port());
    ProxyOptions req2 = new ProxyOptions()
      .setUsername("user2")
      .setPassword("user2")
      .setType(ProxyType.HTTP)
      .setHost("localhost")
      .setPort(proxy.port());
    List<String> res = testPooling(req1, req2, proxy);
    assertEquals(proxy.localAddresses(), res);
  }

  @Test
  public void testHttpProxyAuthPooling2() throws Exception {
    HttpProxy proxy = new HttpProxy().port(SocksProxy.DEFAULT_PORT).username(Arrays.asList("user1"));
    ProxyOptions req1 = new ProxyOptions()
      .setUsername("user1")
      .setPassword("user1")
      .setType(ProxyType.HTTP)
      .setHost("localhost")
      .setPort(proxy.port());
    ProxyOptions req2 = new ProxyOptions()
      .setUsername("user1")
      .setPassword("user1")
      .setType(ProxyType.HTTP)
      .setHost("localhost")
      .setPort(proxy.port());
    List<String> res = testPooling(req1, req2, proxy);
    assertEquals(1, proxy.localAddresses().size());
    assertEquals(Arrays.asList(proxy.localAddresses().get(0), proxy.localAddresses().get(0)), res);
  }

  @Test
  public void testSocksProxyPooling1() throws Exception {
    SocksProxy proxy1 = new SocksProxy().port(SocksProxy.DEFAULT_PORT);
    SocksProxy proxy2 = new SocksProxy().port(SocksProxy.DEFAULT_PORT + 1);
    ProxyOptions req1 = new ProxyOptions()
      .setType(ProxyType.SOCKS5)
      .setHost("localhost")
      .setPort(proxy1.port());
    ProxyOptions req2 = new ProxyOptions()
      .setType(ProxyType.SOCKS5)
      .setHost("localhost")
      .setPort(proxy2.port());
    List<String> res = testPooling(req1, req2, proxy1, proxy2);
    assertEquals(Arrays.asList(proxy1.lastLocalAddress(), proxy2.lastLocalAddress()), res);
  }

  @Test
  public void testSocksProxyPooling2() throws Exception {
    SocksProxy proxy = new SocksProxy().port(SocksProxy.DEFAULT_PORT);
    ProxyOptions req = new ProxyOptions()
      .setType(ProxyType.SOCKS5)
      .setHost("localhost")
      .setPort(proxy.port());
    List<String> res = testPooling(req, req, proxy);
    assertEquals(Arrays.asList(proxy.lastLocalAddress(), proxy.lastLocalAddress()), res);
  }

  @Test
  public void testSocksProxyAuthPooling1() throws Exception {
    SocksProxy proxy = new SocksProxy().port(SocksProxy.DEFAULT_PORT).username(Arrays.asList("user1", "user2"));
    ProxyOptions req1 = new ProxyOptions()
      .setUsername("user1")
      .setPassword("user1")
      .setType(ProxyType.SOCKS5)
      .setHost("localhost")
      .setPort(proxy.port());
    ProxyOptions req2 = new ProxyOptions()
      .setUsername("user2")
      .setPassword("user2")
      .setType(ProxyType.SOCKS5)
      .setHost("localhost")
      .setPort(proxy.port());
    List<String> res = testPooling(req1, req2, proxy);
    assertEquals(proxy.localAddresses(), res);
  }

  @Test
  public void testSocksProxyAuthPooling2() throws Exception {
    SocksProxy proxy = new SocksProxy().port(SocksProxy.DEFAULT_PORT).username(Arrays.asList("user1"));
    ProxyOptions req1 = new ProxyOptions()
      .setUsername("user1")
      .setPassword("user1")
      .setType(ProxyType.SOCKS5)
      .setHost("localhost")
      .setPort(proxy.port());
    ProxyOptions req2 = new ProxyOptions()
      .setUsername("user1")
      .setPassword("user1")
      .setType(ProxyType.SOCKS5)
      .setHost("localhost")
      .setPort(proxy.port());
    List<String> res = testPooling(req1, req2, proxy);
    assertEquals(1, proxy.localAddresses().size());
    assertEquals(Arrays.asList(proxy.localAddresses().get(0), proxy.localAddresses().get(0)), res);
  }

  public List<String> testPooling(ProxyOptions request1, ProxyOptions request2, TestProxyBase... proxies) throws Exception {
    for (TestProxyBase proxy : proxies) {
      proxy.start(vertx);
    }

    client.close();
    client = vertx.createHttpClient(new HttpClientOptions().setMaxPoolSize(1).setKeepAlive(true));

    CompletableFuture<List<String>> ret = new CompletableFuture<>();

    try {
      server.requestHandler(req -> {
        SocketAddress addr = req.connection().remoteAddress();
        req.response().end("" + addr);
      }).listen(onSuccess(s -> {
        RequestOptions baseOptions = new RequestOptions()
          .setHost(DEFAULT_HTTP_HOST)
          .setPort(DEFAULT_HTTP_PORT)
          .setURI("/");
        client.request(new RequestOptions(baseOptions).setProxyOptions(request1))
          .compose(HttpClientRequest::send)
          .compose(HttpClientResponse::body)
          .onComplete(onSuccess(res1 -> {
            client.request(new RequestOptions(baseOptions).setProxyOptions(request2))
              .compose(HttpClientRequest::send)
              .compose(HttpClientResponse::body)
              .onComplete(onSuccess(res2 -> {
                ret.complete(Arrays.asList(res1.toString(), res2.toString()));
              }));
          }));
      }));

      return ret.get(40, TimeUnit.SECONDS);
    } finally {
      for (TestProxyBase proxy : proxies) {
        proxy.stop();
      }
    }
  }

  @Test
  public void testWssHttpProxy() throws Exception {
    startProxy(null, ProxyType.HTTP);
    testWebSocket(createBaseServerOptions().setSsl(true)
      .setKeyCertOptions(Cert.SERVER_JKS.get()), new HttpClientOptions()
      .setSsl(true)
      .setTrustOptions(Cert.SERVER_JKS.get())
      .setProxyOptions(new ProxyOptions()
        .setType(ProxyType.HTTP)
        .setHost(DEFAULT_HTTP_HOST)
        .setPort(proxy.port())), true);
  }

  @Test
  public void testWsHttpProxy() throws Exception {
    startProxy(null, ProxyType.HTTP);
    testWebSocket(createBaseServerOptions(), new HttpClientOptions()
      .setProxyOptions(new ProxyOptions()
        .setType(ProxyType.HTTP)
        .setHost(DEFAULT_HTTP_HOST)
        .setPort(proxy.port())), true);
  }

  @Test
  public void testWssSocks5Proxy() throws Exception {
    startProxy(null, ProxyType.SOCKS5);
    testWebSocket(createBaseServerOptions().setSsl(true)
      .setKeyCertOptions(Cert.SERVER_JKS.get()), new HttpClientOptions()
      .setSsl(true)
      .setTrustOptions(Cert.SERVER_JKS.get())
      .setProxyOptions(new ProxyOptions()
        .setType(ProxyType.SOCKS5)
        .setHost(DEFAULT_HTTP_HOST)
        .setPort(proxy.port())), true);
  }

  @Test
  public void testWsSocks5Proxy() throws Exception {
    startProxy(null, ProxyType.SOCKS5);
    testWebSocket(createBaseServerOptions(), new HttpClientOptions()
      .setProxyOptions(new ProxyOptions()
        .setType(ProxyType.SOCKS5)
        .setHost(DEFAULT_HTTP_HOST)
        .setPort(proxy.port())), true);
  }

  @Test
  public void testWsNonProxyHosts() throws Exception {
    startProxy(null, ProxyType.HTTP);
    testWebSocket(createBaseServerOptions(), new HttpClientOptions()
      .addNonProxyHost("localhost")
      .setProxyOptions(new ProxyOptions()
        .setType(ProxyType.HTTP)
        .setHost(DEFAULT_HTTP_HOST)
        .setPort(proxy.port())), false);
  }

  private void testWebSocket(HttpServerOptions serverOptions, HttpClientOptions clientOptions, boolean proxied) throws Exception {
    server.close();
    server = vertx.createHttpServer(serverOptions);
    client.close();
    client = vertx.createHttpClient(clientOptions);
    server.webSocketHandler(ws -> {
      ws.handler(buff -> {
        ws.write(buff);
        ws.close();
      });
    });
    server.listen(DEFAULT_HTTPS_PORT, DEFAULT_HTTPS_HOST).onSuccess(s -> {
      client.webSocket(DEFAULT_HTTPS_PORT, DEFAULT_HTTPS_HOST, "/").onSuccess(ws -> {
        ws.handler(buff -> {
          ws.close(onSuccess(v -> {
            if (proxied) {
              assertNotNull("request did not go through proxy", proxy.getLastUri());
              if (clientOptions.getProxyOptions().getType() == ProxyType.HTTP) {
                assertEquals("Host header doesn't contain target host", "localhost:4043", proxy.getLastRequestHeaders().get("Host"));
              }
            } else {
              assertNull("request did go through proxy", proxy.getLastUri());
            }
            testComplete();
          }));
        });
        ws.write(Buffer.buffer("Hello world"));
      });
    });
    await();
  }
}
