package com.xiaoke.order;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.order.adapter.product.ProductDetailHttpClient;
import com.xiaoke.order.config.OrderProperties;
import com.xiaoke.order.service.OrderTransactions;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductDetailHttpClientTest {
    HttpServer server; ExecutorService executor;
    AtomicInteger calls=new AtomicInteger();
    int status=200; String body="{\"code\":200,\"data\":{\"id\":1}}"; long delay=0;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        executor=Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/api/product",exchange->{
            calls.incrementAndGet();
            try { if(delay>0) Thread.sleep(delay); }
            catch(InterruptedException ex) { Thread.currentThread().interrupt(); }
            try {
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                if(status==302) exchange.getResponseHeaders().set("Location","/api/redirect");
                exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
    }
    @AfterEach void close() { server.stop(0); executor.shutdownNow(); }
    ProductDetailHttpClient client(int timeout,int deadline) {
        var p=new OrderProperties(); p.setProductBaseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/api");
        p.setReadTimeoutMs(timeout); p.setPrereadDeadlineMs(deadline);
        return new ProductDetailHttpClient(p,new ObjectMapper(),mock(OrderTransactions.class));
    }
    @Test void validPreReadUsesExistingEndpointWithoutRetry() { client(1000,5000).preread(List.of(1L)); assertThat(calls).hasValue(1); }
    @Test void invalidProtocolsAndUpstreamErrorsFailBeforeNextId() {
        for(String invalid:List.of("{bad","{\"code\":200,\"data\":null}","{\"code\":404,\"data\":null}","{\"code\":200,\"data\":{\"id\":2}}")) {
            body=invalid; status=200; calls.set(0);
            assertCode(503,()->client(1000,5000).preread(List.of(1L,2L))); assertThat(calls).hasValue(1);
        }
        status=404; body="{\"code\":404,\"data\":null}"; assertCode(404,()->client(1000,5000).preread(List.of(1L)));
        status=503; body="{\"code\":503,\"data\":null}"; assertCode(503,()->client(1000,5000).preread(List.of(1L)));
        status=302; body="{}"; calls.set(0); assertCode(503,()->client(1000,5000).preread(List.of(1L))); assertThat(calls).hasValue(1);
    }
    @Test void timeoutBoundsTheWholeResponseAndDoesNotRetry() {
        delay=1000;
        long start=System.nanoTime();
        assertCode(503,()->client(100,200).preread(List.of(1L,2L)));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isLessThan(1500);
        assertThat(calls.get()).isLessThanOrEqualTo(1);
    }
    @Test void entireRoundHasOneDeadlineInsteadOfOneTimeoutPerProduct() {
        delay=80;
        long start=System.nanoTime();
        assertCode(503,()->client(1000,300).preread(Collections.nCopies(20,1L)));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isLessThan(1500);
        assertThat(calls.get()).isLessThan(20);
    }
    void assertCode(int code,Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(code));
    }
}
