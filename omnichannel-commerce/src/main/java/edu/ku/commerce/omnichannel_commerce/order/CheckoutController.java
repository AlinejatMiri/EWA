package edu.ku.commerce.omnichannel_commerce.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.ku.commerce.omnichannel_commerce.cart.Cart;
import edu.ku.commerce.omnichannel_commerce.cart.CartRepository;
import edu.ku.commerce.omnichannel_commerce.catalog.Product;
import edu.ku.commerce.omnichannel_commerce.catalog.ProductRepository;
import edu.ku.commerce.omnichannel_commerce.catalog.Promotion;
import edu.ku.commerce.omnichannel_commerce.catalog.PromotionRepository;
import edu.ku.commerce.omnichannel_commerce.common.domain.AppUser;
import edu.ku.commerce.omnichannel_commerce.common.domain.AppUserRepository;
import edu.ku.commerce.omnichannel_commerce.common.domain.Role;
import edu.ku.commerce.omnichannel_commerce.common.errors.ApiError;
import edu.ku.commerce.omnichannel_commerce.common.idempotency.IdempotencyRecord;
import edu.ku.commerce.omnichannel_commerce.common.idempotency.IdempotencyRepository;
import edu.ku.commerce.omnichannel_commerce.inventory.InventoryService;
import edu.ku.commerce.omnichannel_commerce.inventory.Location;
import edu.ku.commerce.omnichannel_commerce.payment.Payment;
import edu.ku.commerce.omnichannel_commerce.payment.PaymentRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class CheckoutController {
    private static final BigDecimal TAX_RATE = new BigDecimal("0.10");

    private final CartRepository carts;
    private final ProductRepository products;
    private final PurchaseOrderRepository orders;
    private final OrderItemRepository orderItems;
    private final OrderStatusHistoryRepository statusHistory;
    private final InventoryService inventory;
    private final PaymentRepository payments;
    private final PromotionRepository promotions;
    private final AppUserRepository users;
    private final IdempotencyRepository keys;
    private final ObjectMapper json;

    public CheckoutController(
            CartRepository carts,
            ProductRepository products,
            PurchaseOrderRepository orders,
            OrderItemRepository orderItems,
            OrderStatusHistoryRepository statusHistory,
            InventoryService inventory,
            PaymentRepository payments,
            PromotionRepository promotions,
            AppUserRepository users,
            IdempotencyRepository keys,
            ObjectMapper json) {
        this.carts = carts;
        this.products = products;
        this.orders = orders;
        this.orderItems = orderItems;
        this.statusHistory = statusHistory;
        this.inventory = inventory;
        this.payments = payments;
        this.promotions = promotions;
        this.users = users;
        this.keys = keys;
        this.json = json;
    }

    public record CheckoutRequest(
            @NotNull Long cartId,
            @NotBlank String paymentMethod,
            @NotBlank String name,
            @Email @NotBlank String email,
            @NotBlank String phone,
            @NotBlank String address,
            String cardNumber,
            String cardExpiry,
            String cardCvc,
            Location location,
            boolean simulatePaymentFailure) {}

    public record CustomerView(String name, String email, String phone, String address) {}

    public record ItemView(Long productId, String name, BigDecimal unitPrice, int quantity) {}

    public record PaymentView(String paymentMethod, String status, BigDecimal amount) {}

    public record BillView(String invoiceNumber, Instant issuedAt, String status) {}

    public record StatusView(String status, Instant changedAt, String changedBy) {}

    public record CheckoutResponse(
            Long id,
            Instant createdAt,
            CustomerView customer,
            String paymentMethod,
            String status,
            String channel,
            BigDecimal subtotal,
            BigDecimal tax,
            BigDecimal discount,
            BigDecimal total,
            List<ItemView> items,
            PaymentView payment,
            BillView bill,
            List<StatusView> statusHistory,
            String returnStatus,
            Long version) {}

    @PostMapping("/checkout")
    @Transactional
    public ResponseEntity<?> checkout(
            @RequestHeader("Idempotency-Key") @NotBlank String key,
            @Valid @RequestBody CheckoutRequest request,
            Authentication authentication)
            throws Exception {
        String requestHash =
                Base64.getEncoder()
                        .encodeToString(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(json.writeValueAsBytes(request)));
        var prior = keys.findById(key);
        if (prior.isPresent()) {
            if (!prior.get().getRequestHash().equals(requestHash)) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency-Key was already used with a different request");
            }
            if (prior.get().getResponseStatus() == HttpStatus.PAYMENT_REQUIRED.value()) {
                ApiError failure = json.readValue(prior.get().getResponseBody(), ApiError.class);
                return ResponseEntity.status(failure.status()).body(failure);
            }
            return ResponseEntity.status(prior.get().getResponseStatus())
                    .body(json.readValue(prior.get().getResponseBody(), CheckoutResponse.class));
        }

        AppUser user =
                users.findByEmail(authentication.getName())
                        .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Cart cart = carts.findById(request.cartId()).orElseThrow(() -> new IllegalArgumentException("Cart not found"));
        if (!cart.getCustomerId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cart belongs to another user");
        }
        boolean branchSale = "BRANCH".equals(cart.getChannel());
        if (branchSale && user.getRole() == Role.CUSTOMER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Branch sales require staff access");
        }
        if (cart.getItems().isEmpty()) {
            throw new IllegalStateException("Cart is empty");
        }
        String paymentMethod = request.paymentMethod().toUpperCase();
        if (!paymentMethod.equals("CREDIT_CARD") && !paymentMethod.equals("CASH_ON_DELIVERY")) {
            throw new IllegalArgumentException("Unsupported payment method");
        }
        if (paymentMethod.equals("CREDIT_CARD")
                && (blank(request.cardNumber()) || blank(request.cardExpiry()) || blank(request.cardCvc()))) {
            throw new IllegalArgumentException("Card number, expiry, and CVC are required for card payments");
        }

        BigDecimal subtotal = BigDecimal.ZERO;
        List<ItemView> itemViews =
                cart.getItems().stream()
                        .map(
                                item -> {
                                    Product product =
                                            products.findById(item.getProductId())
                                                    .filter(Product::isActive)
                                                    .orElseThrow(
                                                            () ->
                                                                    new IllegalArgumentException(
                                                                            "Product not found: " + item.getProductId()));
                                    return new ItemView(
                                            product.getId(), product.getName(), product.getPrice(), item.getQuantity());
                                })
                        .toList();
        for (ItemView item : itemViews) {
            subtotal = subtotal.add(item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())));
        }
        BigDecimal discount = discount(cart.getPromoCode(), subtotal);
        BigDecimal taxableSubtotal = subtotal.subtract(discount).max(BigDecimal.ZERO);
        BigDecimal tax = taxableSubtotal.multiply(TAX_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = taxableSubtotal.add(tax).setScale(2, RoundingMode.HALF_UP);

        Location location = request.location();
        if (location == null) {
            location = branchSale ? Location.BRANCH_A : Location.WEB;
        }
        if (branchSale == (location == Location.WEB)) {
            throw new IllegalArgumentException("Checkout location does not match the cart channel");
        }

        PurchaseOrder order = new PurchaseOrder();
        order.setCustomerId(user.getId());
        order.setChannel(cart.getChannel());
        order.setStatus(OrderStatus.PENDING);
        order.setSubtotal(subtotal.setScale(2, RoundingMode.HALF_UP));
        order.setDiscount(discount);
        order.setTax(tax);
        order.setTotal(total);
        order.setCustomerName(request.name().trim());
        order.setCustomerEmail(request.email().trim());
        order.setCustomerPhone(request.phone().trim());
        order.setCustomerAddress(request.address().trim());
        order.setPaymentMethod(paymentMethod);
        order = orders.saveAndFlush(order);

        for (var cartItem : cart.getItems()) {
            inventory.reserve(order.getId(), cartItem.getProductId(), cartItem.getQuantity(), location);
            OrderItem savedItem = new OrderItem();
            ItemView item =
                    itemViews.stream()
                            .filter(candidate -> candidate.productId().equals(cartItem.getProductId()))
                            .findFirst()
                            .orElseThrow();
            savedItem.setOrderId(order.getId());
            savedItem.setProductId(item.productId());
            savedItem.setName(item.name());
            savedItem.setUnitPrice(item.unitPrice());
            savedItem.setQuantity(item.quantity());
            orderItems.save(savedItem);
        }

        Payment payment = new Payment();
        payment.setOrderId(order.getId());
        payment.setIdempotencyKey(key);
        payment.setAmount(total);
        boolean paymentFailed =
                request.simulatePaymentFailure()
                        || (paymentMethod.equals("CREDIT_CARD")
                                && "4000000000000002".equals(request.cardNumber().replaceAll("\\s", "")));
        payment.setStatus(
                paymentFailed
                        ? Payment.Status.FAILED
                        : paymentMethod.equals("CREDIT_CARD")
                                ? Payment.Status.CAPTURED
                                : Payment.Status.AUTHORIZED);
        payments.save(payment);

        if (paymentFailed) {
            inventory.release(order.getId());
            order.setStatus(OrderStatus.FAILED);
            orders.save(order);
            recordStatus(order, user.getEmail());
            ApiError failure =
                    new ApiError(
                            HttpStatus.PAYMENT_REQUIRED.value(),
                            HttpStatus.PAYMENT_REQUIRED.getReasonPhrase(),
                            "Payment was declined",
                            "/api/checkout",
                            Instant.now());
            saveIdempotency(key, requestHash, failure.status(), json.writeValueAsString(failure));
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(failure);
        }

        inventory.commit(order.getId());
        order.setStatus(OrderStatus.CONFIRMED);
        order = orders.saveAndFlush(order);
        recordStatus(order, user.getEmail());
        if (cart.getPromoCode() != null) {
            promotions.findByCodeIgnoreCase(cart.getPromoCode()).ifPresent(p -> p.setUsageCount(p.getUsageCount() + 1));
        }
        cart.getItems().clear();
        cart.setPromoCode(null);
        carts.save(cart);

        CheckoutResponse response = orderResponse(order, payment, itemViews);
        saveIdempotency(key, requestHash, HttpStatus.CREATED.value(), json.writeValueAsString(response));
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etag(order)).body(response);
    }

    private BigDecimal discount(String code, BigDecimal subtotal) {
        if (code == null || code.isBlank()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        Promotion promotion =
                promotions.findByCodeIgnoreCase(code)
                        .orElseThrow(() -> new IllegalStateException("Promotion is no longer available"));
        Instant now = Instant.now();
        if (now.isBefore(promotion.getStartsAt())
                || now.isAfter(promotion.getEndsAt())
                || promotion.getUsageCount() >= promotion.getUsageLimit()) {
            throw new IllegalStateException("Promotion is expired or has reached its usage limit");
        }
        BigDecimal amount =
                promotion.getKind() == Promotion.Kind.PERCENT
                        ? subtotal.multiply(promotion.getValue()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP)
                        : promotion.getValue();
        return amount.min(subtotal).setScale(2, RoundingMode.HALF_UP);
    }

    private CheckoutResponse orderResponse(PurchaseOrder order, Payment payment, List<ItemView> items) {
        return new CheckoutResponse(
                order.getId(),
                order.getCreatedAt(),
                new CustomerView(
                        order.getCustomerName(),
                        order.getCustomerEmail(),
                        order.getCustomerPhone(),
                        order.getCustomerAddress()),
                order.getPaymentMethod(),
                order.getStatus().name(),
                order.getChannel(),
                order.getSubtotal(),
                order.getTax(),
                order.getDiscount(),
                order.getTotal(),
                items,
                new PaymentView(order.getPaymentMethod(), payment.getStatus().name(), payment.getAmount()),
                new BillView("INV-" + order.getId(), order.getCreatedAt(), order.getStatus().name()),
                statusHistory.findByOrderIdOrderByChangedAtAsc(order.getId()).stream()
                        .map(entry -> new StatusView(entry.getStatus().name(), entry.getChangedAt(), entry.getChangedBy()))
                        .toList(),
                null,
                order.getVersion());
    }

    private void recordStatus(PurchaseOrder order, String changedBy) {
        OrderStatusHistory entry = new OrderStatusHistory();
        entry.setOrderId(order.getId());
        entry.setStatus(order.getStatus());
        entry.setChangedBy(changedBy);
        statusHistory.save(entry);
    }

    private void saveIdempotency(String key, String hash, int status, String body) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setKey(key);
        record.setRequestHash(hash);
        record.setResponseStatus(status);
        record.setResponseBody(body);
        keys.save(record);
    }

    private String etag(PurchaseOrder order) {
        return "\"" + order.getVersion() + "\"";
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
