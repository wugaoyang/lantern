package org.lantern.simple;

import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import java.net.InetSocketAddress;

import org.lantern.proxy.GetModeHttpFilters;
import org.lantern.proxy.GiveModeHttpFilters;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.HttpFiltersSourceAdapter;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.TransportProtocol;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * A really basic Give mode proxy that listens with both TCP and UDT and trusts
 * all Get proxies.
 * </p>
 * 
 * <p>
 * Run like this:
 * </p>
 * 
 * <pre>
 * ./launch org.lantern.simple.Give 46000 ../too-many-secrets/littleproxy_keystore.jks
 * </pre>
 */
public class Give {
    private static final Logger LOG = LoggerFactory.getLogger(Give.class);

    private String keyStorePath;
    private String expectedAuthToken;
    private int httpsPort;
    private int httpPort;
    private int udtPort;
    private HttpProxyServer server;

    public static void main(String[] args) throws Exception {
        new Give(args).start();
    }

    public Give(String[] args) {
        this.httpPort = Integer.parseInt(args[0]);
        this.httpsPort = Integer.parseInt(args[1]);
        this.udtPort = Integer.parseInt(args[2]);
        this.keyStorePath = args[3];
        this.expectedAuthToken = args[4];
    }

    public void start() {
        startTcp();
        startUdt();

    }

    private void startTcp() {
        LOG.info("Starting Plain Text Give proxy at TCP port {}", httpPort);
        DefaultHttpProxyServer.bootstrap()
                .withName("Give-PlainText")
                .withPort(httpPort)
                .withAllowLocalOnly(false)
                .withListenOnAllAddresses(true)
                // Use a filter to respond with 404 to http requests
                .withFiltersSource(new HttpFiltersSourceAdapter() {
                    @Override
                    public HttpFilters filterRequest(HttpRequest originalRequest) {
                        return new GiveModeHttpFilters(originalRequest) {
                            @Override
                            public HttpResponse requestPre(HttpObject httpObject) {
                                if (httpObject instanceof HttpRequest) {
                                    return new DefaultFullHttpResponse(
                                            HttpVersion.HTTP_1_1,
                                            HttpResponseStatus.NOT_FOUND);
                                }
                                return super.requestPre(httpObject);
                            }
                        };
                    }
                })
                .start();

        LOG.info(
                "Starting TLS Give proxy at TCP port {}", httpPort);
        server = DefaultHttpProxyServer.bootstrap()
                .withName("Give-PlainText")
                .withPort(httpsPort)
                .withAllowLocalOnly(false)
                .withListenOnAllAddresses(true)
                .withSslEngineSource(new SimpleSslEngineSource(keyStorePath))
                .withAuthenticateSslClients(false)

                // Use a filter to deny requests other than those contains the
                // right auth token
                .withFiltersSource(new HttpFiltersSourceAdapter() {
                    @Override
                    public HttpFilters filterRequest(HttpRequest originalRequest) {
                        return new GiveModeHttpFilters(originalRequest) {
                            @Override
                            public HttpResponse requestPre(HttpObject httpObject) {
                                if (httpObject instanceof HttpRequest) {
                                    HttpRequest req = (HttpRequest) httpObject;
                                    String authToken = req
                                            .headers()
                                            .get(GetModeHttpFilters.X_LANTERN_AUTH_TOKEN);
                                    if (!expectedAuthToken.equals(authToken)) {
                                        return new DefaultFullHttpResponse(
                                                HttpVersion.HTTP_1_1,
                                                HttpResponseStatus.NOT_FOUND);
                                    }
                                }
                                return super.requestPre(httpObject);
                            }
                        };
                    }
                })
                .start();
    }

    private void startUdt() {
        LOG.info("Starting Give proxy at UDT port {}", udtPort);
        server.clone()
                .withAddress(
                        new InetSocketAddress(server.getListenAddress()
                                .getAddress(), udtPort))
                .withTransportProtocol(TransportProtocol.UDT).start();
    }
}