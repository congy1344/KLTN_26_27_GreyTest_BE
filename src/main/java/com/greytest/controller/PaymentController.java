package com.greytest.controller;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.greytest.dto.payment.CreatePaymentRequest;
import com.greytest.dto.payment.PaymentCallbackResponse;
import com.greytest.dto.payment.PaymentUrlResponse;
import com.greytest.entity.AuthUser;
import com.greytest.entity.PaymentTransaction;
import com.greytest.entity.enums.PaymentStatus;
import com.greytest.entity.enums.UserTier;
import com.greytest.repository.AuthUserRepository;
import com.greytest.repository.PaymentTransactionRepository;
import com.greytest.service.AuthService;
import com.greytest.service.UserService;
import com.greytest.service.payment.VnpayService;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Controller xử lý luồng thanh toán VNPay nâng cấp các gói dịch vụ (Plus, Pro).
 */
@Slf4j
@RestController
@RequestMapping("/api/payment")
public class PaymentController {

    private final VnpayService vnpayService;
    private final PaymentTransactionRepository paymentRepository;
    private final AuthService authService;
    private final UserService userService;
    private final AuthUserRepository userRepository;

    public PaymentController(
            VnpayService vnpayService,
            PaymentTransactionRepository paymentRepository,
            AuthService authService,
            UserService userService,
            AuthUserRepository userRepository) {
        this.vnpayService = vnpayService;
        this.paymentRepository = paymentRepository;
        this.authService = authService;
        this.userService = userService;
        this.userRepository = userRepository;
    }

    /**
     * Tạo đường dẫn thanh toán trực tuyến qua VNPay.
     * Giá thử nghiệm theo yêu cầu: Gói PLUS = 1.000 VNĐ, Gói PRO = 2.000 VNĐ.
     */
    @PostMapping("/create-vnpay-url")
    public ResponseEntity<PaymentUrlResponse> createPaymentUrl(
            @RequestHeader("Authorization") String authorization,
            @RequestBody CreatePaymentRequest request,
            HttpServletRequest servletRequest) {

        AuthUser user = authService.currentUser(authorization);
        UserTier targetTier = request.tier();

        if (targetTier == null || targetTier == UserTier.FREE) {
            return ResponseEntity.badRequest().build();
        }

        // Đặt giá 1.000 - 2.000 VNĐ cho môi trường thử nghiệm đồ án
        long amount = (targetTier == UserTier.PLUS) ? 1_000L : 2_000L;

        String orderId = "GT_" + System.currentTimeMillis() + "_" + ThreadLocalRandom.current().nextInt(1000, 9999);
        String orderInfo = "GreyTest - Nang cap goi " + targetTier.name() + " (" + user.getEmail() + ")";

        PaymentTransaction transaction = new PaymentTransaction();
        transaction.setUserId(user.getId());
        transaction.setOrderId(orderId);
        transaction.setTier(targetTier);
        transaction.setAmount(amount);
        transaction.setStatus(PaymentStatus.PENDING);
        paymentRepository.save(transaction);

        String ipAddress = servletRequest.getHeader("X-Forwarded-For");
        if (ipAddress == null || ipAddress.isBlank()) {
            ipAddress = servletRequest.getRemoteAddr();
        }

        String paymentUrl = vnpayService.createPaymentUrl(orderId, amount, orderInfo, ipAddress);

        return ResponseEntity.ok(new PaymentUrlResponse(paymentUrl, orderId, amount));
    }

    /**
     * Tiếp nhận callback khi người dùng hoàn tất giao dịch trên VNPay.
     */
    @GetMapping("/vnpay-callback")
    public ResponseEntity<PaymentCallbackResponse> handleVnpayCallback(
            @RequestParam Map<String, String> params) {

        log.info("Nhận callback từ VNPay: {}", params);

        boolean isValidSignature = vnpayService.verifySignature(params);
        if (!isValidSignature) {
            log.warn("Chữ ký VNPay không hợp lệ!");
            return ResponseEntity.badRequest().body(new PaymentCallbackResponse(
                    PaymentStatus.FAILED,
                    "Chữ ký xác thực giao dịch không hợp lệ",
                    params.get("vnp_TxnRef"),
                    null,
                    0L,
                    null
            ));
        }

        String orderId = params.get("vnp_TxnRef");
        String responseCode = params.get("vnp_ResponseCode");
        String transactionNo = params.get("vnp_TransactionNo");
        String bankCode = params.get("vnp_BankCode");

        PaymentTransaction transaction = paymentRepository.findByOrderId(orderId).orElse(null);
        if (transaction == null) {
            return ResponseEntity.badRequest().body(new PaymentCallbackResponse(
                    PaymentStatus.FAILED,
                    "Không tìm thấy thông tin đơn hàng " + orderId,
                    orderId,
                    null,
                    0L,
                    transactionNo
            ));
        }

        transaction.setVnpTxnRef(orderId);
        transaction.setVnpTransactionNo(transactionNo);
        transaction.setVnpBankCode(bankCode);
        transaction.setVnpResponseCode(responseCode);

        // Mã phản hồi "00" biểu thị giao dịch thành công tại VNPay
        if ("00".equals(responseCode)) {
            transaction.setStatus(PaymentStatus.SUCCESS);
            paymentRepository.save(transaction);

            // Nâng cấp gói cho người dùng tương ứng
            userRepository.findById(transaction.getUserId()).ifPresent(user -> {
                userService.upgradeToTier(user, transaction.getTier());
            });

            return ResponseEntity.ok(new PaymentCallbackResponse(
                    PaymentStatus.SUCCESS,
                    "Thanh toán nâng cấp gói " + transaction.getTier().name() + " thành công!",
                    orderId,
                    transaction.getTier(),
                    transaction.getAmount(),
                    transactionNo
            ));
        } else {
            transaction.setStatus(PaymentStatus.FAILED);
            paymentRepository.save(transaction);

            return ResponseEntity.ok(new PaymentCallbackResponse(
                    PaymentStatus.FAILED,
                    "Giao dịch không thành công hoặc người dùng đã hủy (Mã phản hồi: " + responseCode + ")",
                    orderId,
                    transaction.getTier(),
                    transaction.getAmount(),
                    transactionNo
            ));
        }
    }

    /**
     * Lấy lịch sử giao dịch thanh toán của người dùng hiện tại.
     */
    @GetMapping("/history")
    public List<PaymentTransaction> getPaymentHistory(
            @RequestHeader("Authorization") String authorization) {
        AuthUser user = authService.currentUser(authorization);
        return paymentRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
    }
}
