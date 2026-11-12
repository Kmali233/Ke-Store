package com.xiaoke.order.payment;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.AlipayConfig;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.domain.AlipayTradeQueryModel;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.xiaoke.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.security.KeyFactory;
import java.security.interfaces.RSAKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.xiaoke.order.payment.PaymentNotification.*;

@Component
@Profile("alipay-sandbox & !payment-test & !payment-demo & !prod")
public class AlipaySandboxGateway implements PaymentGateway {
    public static final String CHANNEL = "ALIPAY_SANDBOX";
    public static final String NOTIFY_PATH = "/payment/alipay/notify";
    private static final Set<String> SANDBOX_GATEWAYS = Set.of(
            "https://openapi-sandbox.dl.alipaydev.com/gateway.do",
            "https://openapi.alipaydev.com/gateway.do");

    private final Account account;
    private final AlipayClient client;
    private final String gateway;
    private final String platformKey;
    private final String notifyUrl;
    private final String buyerOrigin;

    public AlipaySandboxGateway(
            @Value("${xiaoke.payment.alipay.gateway}") String gateway,
            @Value("${xiaoke.payment.alipay.app-id}") String appId,
            @Value("${xiaoke.payment.alipay.seller-id}") String sellerId,
            @Value("${xiaoke.payment.alipay.private-key}") String privateKey,
            @Value("${xiaoke.payment.alipay.public-key}") String platformKey,
            @Value("${xiaoke.payment.alipay.notify-url}") String notifyUrl,
            @Value("${xiaoke.payment.alipay.buyer-origin}") String buyerOrigin) {
        if (!SANDBOX_GATEWAYS.contains(gateway)) {
            throw new IllegalStateException("支付宝网关必须是受支持的 HTTPS 沙箱地址");
        }
        if (!appId.matches("[0-9]{1,32}") || !sellerId.matches("[0-9]{1,32}")) {
            throw new IllegalStateException("请配置沙箱 App ID 和卖家 PID");
        }
        this.notifyUrl = endpoint(notifyUrl, "/api" + NOTIFY_PATH, false);
        this.buyerOrigin = endpoint(buyerOrigin, "", true);
        this.gateway = gateway;
        this.account = new Account(CHANNEL, appId, sellerId);
        this.platformKey = platformKey;
        this.client = createClient(privateKey);
    }

    @Override
    public Account account() {
        return account;
    }

    @Override
    public String checkout(PaymentOrder payment, String orderNo) {
        var model = new AlipayTradePagePayModel();
        model.setOutTradeNo(payment.paymentNo());
        model.setTotalAmount(payment.totalAmount().toPlainString());
        model.setSubject("小Ke商城沙箱订单 " + orderNo);
        model.setProductCode("FAST_INSTANT_TRADE_PAY");
        var request = new AlipayTradePagePayRequest();
        request.setBizModel(model);
        request.setNotifyUrl(notifyUrl);
        request.setReturnUrl(buyerOrigin + "/orders/" + orderNo + "?paymentReturn=1");
        try {
            var response = client.pageExecute(request, "GET");
            String url = response == null ? null : response.getBody();
            if (url == null || !url.startsWith(gateway + "?")) {
                throw unavailable();
            }
            return url;
        } catch (AlipayApiException ex) {
            throw unavailable();
        }
    }

    @Override
    public Optional<Trade> query(PaymentOrder payment) {
        var model = new AlipayTradeQueryModel();
        model.setOutTradeNo(payment.paymentNo());
        var request = new AlipayTradeQueryRequest();
        request.setBizModel(model);
        try {
            // With the configured Alipay public key, execute verifies a successful response.
            var result = client.execute(request);
            if (result == null) {
                throw unavailable();
            }
            if (!result.isSuccess()) {
                if ("ACQ.TRADE_NOT_EXIST".equals(result.getSubCode())) {
                    return Optional.empty();
                }
                throw unavailable();
            }
            if (!payment.paymentNo().equals(result.getOutTradeNo())) {
                throw invalidTrade();
            }
            // The query API omits app_id/seller_id. It is scoped to this direct merchant's signed application.
            return Optional.of(new Trade(account, result.getOutTradeNo(), result.getTradeNo(),
                    result.getTotalAmount(), parseState(result.getTradeStatus())));
        } catch (AlipayApiException ex) {
            throw unavailable();
        }
    }

    @Override
    public Trade verifyNotification(Map<String, String> parameters) {
        return PaymentNotification.verify(parameters, platformKey, account);
    }

    private AlipayClient createClient(String privateKey) {
        try {
            var rsa = KeyFactory.getInstance("RSA");
            var signingKey = (RSAKey) rsa.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey)));
            var verificationKey = (RSAKey) rsa.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(platformKey)));
            if (signingKey.getModulus().bitLength() < 2048 || verificationKey.getModulus().bitLength() < 2048
                    || signingKey.getModulus().equals(verificationKey.getModulus())) {
                throw new IllegalArgumentException();
            }
            var config = new AlipayConfig();
            config.setServerUrl(gateway);
            config.setAppId(account.appId());
            config.setPrivateKey(privateKey);
            config.setAlipayPublicKey(platformKey);
            config.setCharset(UTF8);
            config.setSignType(RSA2);
            config.setConnectTimeout(2000);
            config.setReadTimeout(4500);
            return new DefaultAlipayClient(config);
        } catch (Exception ex) {
            throw new IllegalStateException("请检查应用 PKCS#8 私钥和支付宝公钥配置，使用不同的 2048 位以上 RSA 密钥");
        }
    }

    private static String endpoint(String value, String path, boolean allowLocalHttp) {
        try {
            var uri = URI.create(value);
            var host = uri.getHost();
            boolean local = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
            boolean schemeAllowed = "https".equals(uri.getScheme())
                    || (allowLocalHttp && local && "http".equals(uri.getScheme()));
            if (host != null && schemeAllowed && (allowLocalHttp || !local)
                    && path.equals(uri.getRawPath()) && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null) {
                return value;
            }
        } catch (IllegalArgumentException ex) {
            // Do not echo malformed configuration.
        }
        throw new IllegalStateException("请检查支付宝沙箱通知地址或买家端 Origin");
    }

    private static BusinessException unavailable() {
        return new BusinessException(503, "支付宝沙箱暂时不可用，请稍后查询");
    }
}
