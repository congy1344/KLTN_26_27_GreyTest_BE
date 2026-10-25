package com.greytest.service.payment;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VnpayServiceTest {

    private VnpayService vnpayService;

    @BeforeEach
    void setUp() {
        vnpayService = new VnpayService(
                "CGXZLS0Z",
                "XNBCJFAKAZQSGTARRLGCHVZWCIOIGSHN",
                "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html",
                "http://localhost:3000/payment/vnpay-return"
        );
    }

    @ParameterizedTest
    @CsvSource({"10000, 1000000", "20000, 2000000"})
    void createPaymentUrl_GeneratesValidUrlWithExpectedParameters(long amount, String vnpAmount) {
        String url = vnpayService.createPaymentUrl("ORDER_123", amount, "Test Payment", "127.0.0.1");

        assertThat(url).startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?");
        assertThat(url).contains("vnp_TmnCode=CGXZLS0Z");
        assertThat(url).contains("vnp_Amount=" + vnpAmount + "&");
        assertThat(url).contains("vnp_TxnRef=ORDER_123");
        assertThat(url).contains("vnp_ReturnUrl=http%3A%2F%2Flocalhost%3A3000%2Fpayment%2Fvnpay-return");
        assertThat(url).contains("vnp_SecureHash=");
    }

    @Test
    void createPaymentUrl_WithCustomReturnUrl_UsesCustomUrl() {
        String url = vnpayService.createPaymentUrl("ORDER_123", 1000L, "Test Payment", "127.0.0.1", "https://my-domain.com/return");

        assertThat(url).contains("vnp_ReturnUrl=https%3A%2F%2Fmy-domain.com%2Freturn");
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
