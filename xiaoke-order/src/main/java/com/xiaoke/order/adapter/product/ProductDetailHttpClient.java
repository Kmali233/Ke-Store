package com.xiaoke.order.adapter.product;
import com.fasterxml.jackson.databind.*;
import com.xiaoke.common.exception.BusinessException;
import com.xiaoke.order.config.OrderProperties;
import com.xiaoke.order.service.OrderTransactions;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** HTTP在事务与按日锁之外；禁止重定向和重试，整轮预算最多5秒。 */
@Component
public class ProductDetailHttpClient {
    private final OrderProperties properties;
    private final ObjectMapper json;
    private final OrderTransactions transactions;
    private final HttpClient client;
    private final String base;
    public ProductDetailHttpClient(OrderProperties properties,ObjectMapper json,OrderTransactions transactions) {
        this.properties=properties; this.json=json; this.transactions=transactions;
        URI uri;
        try { uri=URI.create(properties.getProductBaseUrl()); }
        catch (RuntimeException bad) { throw new IllegalStateException("Invalid product base URL"); }
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost()==null
                || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null) {
            throw new IllegalStateException("Invalid product base URL");
        }
        base=uri.toString().replaceAll("/+$","");
        client=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public void preread(List<Long> ids) {
        transactions.outsideTransaction();
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(properties.getPrereadDeadlineMs());
        for (Long id:ids) {
            long remaining=deadline-System.nanoTime();
            if (remaining<=0) { throw unavailable(); }
            long budget=Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(properties.getReadTimeoutMs()));
            var request=HttpRequest.newBuilder(URI.create(base+"/product/"+id))
                    .timeout(Duration.ofNanos(budget)).header("Accept","application/json").GET().build();
            var pending=client.sendAsync(request,HttpResponse.BodyHandlers.ofByteArray());
            try {
                var response=pending.get(budget,TimeUnit.NANOSECONDS);
                if (!response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).startsWith("application/json")
                        || response.body().length>1048576) { throw unavailable(); }
                JsonNode body=json.readTree(response.body());
                if (body==null || !body.isObject() || !body.path("code").isIntegralNumber() || !body.path("code").canConvertToInt()
                        || body.path("code").intValue()!=response.statusCode()) { throw unavailable(); }
                if (response.statusCode()==404 && body.has("data") && body.get("data").isNull()) { throw new BusinessException(404,"商品不可用"); }
                var data=body.path("data");
                if (response.statusCode()!=200 || !data.isObject() || !data.path("id").isIntegralNumber()
                        || !data.path("id").canConvertToLong() || data.path("id").longValue()!=id) { throw unavailable(); }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw unavailable(); }
            catch (BusinessException known) { throw known; }
            catch (Exception failure) { throw unavailable(); }
            finally { if (!pending.isDone()) { pending.cancel(true); } }
            if (System.nanoTime()>deadline) { throw unavailable(); }
        }
    }
    private BusinessException unavailable() { return new BusinessException(503,"商品服务暂不可用"); }
}
