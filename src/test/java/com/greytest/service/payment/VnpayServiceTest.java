package com.greytest.service.payment;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VnpayServiceTest {

    private VnpayService vnpayService;

    @BeforeEach
    void setUp() {
        vnpayService = new VnpayService(
                "CGXZLS0Z",
                "XNBCJFAKAZQSGTARRLGCHVZWCIOIGSHN",
                "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html",
                "http://localhost:5173/payment/vnpay-return"
        );
    }

    @Test
    void createPaymentUrl_GeneratesValidUrlWithExpectedParameters() {
        String url = vnpayService.createPaymentUrl("ORDER_123", 1000L, "Test Payment", "127.0.0.1");

        assertThat(url).startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?");
        assertThat(url).contains("vnp_TmnCode=CGXZLS0Z");
        assertThat(url).contains("vnp_Amount=100000"); // 1000 * 100
        assertThat(url).contains("vnp_TxnRef=ORDER_123");
        assertThat(url).contains("vnp_SecureHash=");
    }

    @Test
    void verifySignature_ReturnsTrueForValidSignature() {
        Map<String, String> params = new HashMap<>();
        params.put("vnp_Amount", "100000");
        params.put("vnp_TxnRef", "ORDER_123");
        params.put("vnp_ResponseCode", "00");

        String hashData = "vnp_Amount=100000&vnp_ResponseCode=00&vnp_TxnRef=ORDER_123";
        String validHash = VnpayService.hmacSHA512("XNBCJFAKAZQSGTARRLGCHVZWCIOIGSHN", hashData);
        params.put("vnp_SecureHash", validHash);

        boolean isValid = vnpayService.verifySignature(params);
        assertThat(isValid).isTrue();
    }

    @Test
    void verifySignature_ReturnsFalseForTamperedData() {
        Map<String, String> params = new HashMap<>();
        params.put("vnp_Amount", "100000");
        params.put("vnp_TxnRef", "ORDER_123");
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_SecureHash", "invalid_hash_value");

        boolean isValid = vnpayService.verifySignature(params);
        assertThat(isValid).isFalse();
    }
}
