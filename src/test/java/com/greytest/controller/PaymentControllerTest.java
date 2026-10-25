package com.greytest.controller;

import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mockito;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import com.greytest.dto.payment.CreatePaymentRequest;
import com.greytest.dto.payment.PaymentCallbackResponse;
import com.greytest.dto.payment.PaymentUrlResponse;
import com.greytest.entity.AuthUser;
import com.greytest.entity.PaymentTransaction;
import com.greytest.entity.UsageQuota;
import com.greytest.entity.enums.PaymentStatus;
import com.greytest.entity.enums.UserTier;
import com.greytest.repository.AuthUserRepository;
import com.greytest.repository.PaymentTransactionRepository;
import com.greytest.service.AuthService;
import com.greytest.service.UserService;
import com.greytest.service.UsageQuotaService;
import com.greytest.service.UserActivityService;
import com.greytest.service.payment.VnpayService;

import java.util.Map;

class PaymentControllerTest {

    private VnpayService vnpayService;
    private PaymentTransactionRepository paymentRepository;
    private AuthService authService;
    private UserService userService;
    private AuthUserRepository userRepository;
    private PaymentController controller;

    @BeforeEach
    void setUp() {
        vnpayService = Mockito.mock(VnpayService.class);
        paymentRepository = Mockito.mock(PaymentTransactionRepository.class);
        authService = Mockito.mock(AuthService.class);
        userService = Mockito.mock(UserService.class);
        userRepository = Mockito.mock(AuthUserRepository.class);

        controller = new PaymentController(vnpayService, paymentRepository, authService, userService, userRepository);
    }

    @Test
    void getPaymentHistory_UsesAuthenticatedUserId() {
        AuthUser user = new AuthUser();
        user.setId(5L);
        when(authService.currentUser("Bearer token")).thenReturn(user);
        PaymentTransaction transaction = new PaymentTransaction();
        transaction.setUserId(5L);
        transaction.setOrderId("OWN_ORDER");
        when(paymentRepository.findByUserIdOrderByCreatedAtDesc(5L)).thenReturn(List.of(transaction));

        var history = controller.getPaymentHistory("Bearer token");

        assertThat(history).containsExactly(transaction);
        verify(paymentRepository).findByUserIdOrderByCreatedAtDesc(5L);
    }

    @Test
    void getPaymentHistory_RejectsUnauthenticatedAccessBeforeReadingTransactions() {
        when(authService.currentUser("Bearer invalid")).thenThrow(new IllegalArgumentException("Invalid session"));

        assertThatThrownBy(() -> controller.getPaymentHistory("Bearer invalid"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(paymentRepository);
    }

    @Test
    void createPaymentUrl_WithPlusTier_Returns10000VndAndUrl() {
        AuthUser user = new AuthUser();
        user.setId(5L);
        user.setEmail("user@test.com");
        when(authService.currentUser("Bearer token")).thenReturn(user);
        when(vnpayService.createPaymentUrl(anyString(), eq(10000L), anyString(), anyString()))
                .thenReturn("https://sandbox.vnpayment.vn/test-url");

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setRemoteAddr("127.0.0.1");

        ResponseEntity<PaymentUrlResponse> response = controller.createPaymentUrl(
                "Bearer token",
                new CreatePaymentRequest(UserTier.PLUS),
                servletRequest
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().amount()).isEqualTo(10000L);
        assertThat(response.getBody().paymentUrl()).isEqualTo("https://sandbox.vnpayment.vn/test-url");
        verify(paymentRepository).save(argThat(tx -> tx.getAmount() == 10000L && tx.getTier() == UserTier.PLUS));
    }

    @Test
    void createPaymentUrl_WithProTier_Returns20000VndAndUrl() {
        AuthUser user = new AuthUser();
        user.setId(5L);
        user.setEmail("user@test.com");
        when(authService.currentUser("Bearer token")).thenReturn(user);
        when(vnpayService.createPaymentUrl(anyString(), eq(20000L), anyString(), anyString()))
                .thenReturn("https://sandbox.vnpayment.vn/test-url-pro");

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setRemoteAddr("127.0.0.1");

        ResponseEntity<PaymentUrlResponse> response = controller.createPaymentUrl(
                "Bearer token",
                new CreatePaymentRequest(UserTier.PRO),
                servletRequest
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().amount()).isEqualTo(20000L);
        assertThat(response.getBody().paymentUrl()).isEqualTo("https://sandbox.vnpayment.vn/test-url-pro");
        verify(paymentRepository).save(argThat(tx -> tx.getAmount() == 20000L && tx.getTier() == UserTier.PRO));
    }

    @Test
    void handleVnpayCallback_WithCode00_SucceedsAndUpgradesTier() {
        when(vnpayService.verifySignature(anyMap())).thenReturn(true);

        PaymentTransaction tx = new PaymentTransaction();
        tx.setId(10L);
        tx.setUserId(5L);
        tx.setOrderId("ORDER_999");
        tx.setTier(UserTier.PRO);
        tx.setAmount(2000L);
        tx.setStatus(PaymentStatus.PENDING);

        when(paymentRepository.findByOrderId("ORDER_999")).thenReturn(Optional.of(tx));

        AuthUser user = new AuthUser();
        user.setId(5L);
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));

        Map<String, String> callbackParams = Map.of(
                "vnp_TxnRef", "ORDER_999",
                "vnp_ResponseCode", "00",
                "vnp_TransactionNo", "14285700",
                "vnp_BankCode", "NCB",
                "vnp_SecureHash", "dummy"
        );

        ResponseEntity<PaymentCallbackResponse> response = controller.handleVnpayCallback(callbackParams);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(tx.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        verify(userService).upgradeToTier(user, UserTier.PRO);
    }

    @Test
    void handleVnpayCallback_WithInvalidSignature_ReturnsBadRequest() {
        when(vnpayService.verifySignature(anyMap())).thenReturn(false);

        Map<String, String> callbackParams = Map.of(
                "vnp_TxnRef", "ORDER_999",
                "vnp_SecureHash", "invalid"
        );

        ResponseEntity<PaymentCallbackResponse> response = controller.handleVnpayCallback(callbackParams);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void replayOfSuccessfulPaymentDoesNotRestoreCanceledPlan() {
        AuthUser user = new AuthUser();
        user.setId(5L);
        user.setTier(UserTier.FREE);
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));
        UsageQuotaService quotas = Mockito.mock(UsageQuotaService.class);
        UsageQuota paid = new UsageQuota();
        paid.setQuotaLimit(300);
        paid.setQuotaUsed(0);
        paid.setPeriodStart(java.time.LocalDate.of(2026, 10, 22));
        UsageQuota free = new UsageQuota();
        free.setQuotaLimit(100);
        free.setQuotaUsed(25);
        free.setPeriodStart(java.time.LocalDate.of(2026, 10, 1));
        when(quotas.upgradeToTier(5L, UserTier.PRO)).thenReturn(paid);
        when(quotas.downgradeToFree(5L)).thenReturn(free);
        when(quotas.getDefaultLimit()).thenReturn(100);
        UserService accounts = new UserService(userRepository, quotas, Mockito.mock(UserActivityService.class));
        PaymentController payments = new PaymentController(vnpayService, paymentRepository, authService, accounts, userRepository);
        PaymentTransaction tx = new PaymentTransaction();
        tx.setUserId(5L);
        tx.setOrderId("ORDER_PAID");
        tx.setTier(UserTier.PRO);
        tx.setAmount(2000L);
        tx.setStatus(PaymentStatus.PENDING);
        when(paymentRepository.findByOrderId("ORDER_PAID")).thenReturn(Optional.of(tx));
        when(vnpayService.verifySignature(anyMap())).thenReturn(true);
        Map<String, String> params = Map.of("vnp_TxnRef", "ORDER_PAID", "vnp_ResponseCode", "00",
                "vnp_TransactionNo", "14285700");

        payments.handleVnpayCallback(params);
        assertThat(user.getTier()).isEqualTo(UserTier.PRO);
        accounts.cancelSubscription(user);
        assertThat(user.getTier()).isEqualTo(UserTier.FREE);

        var replay = payments.handleVnpayCallback(params);

        assertThat(replay.getBody().status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(replay.getBody().transactionNo()).isEqualTo("14285700");
        assertThat(user.getTier()).isEqualTo(UserTier.FREE);
        verify(quotas, Mockito.times(1)).upgradeToTier(5L, UserTier.PRO);
        verify(paymentRepository, Mockito.times(1)).save(tx);
    }
}
