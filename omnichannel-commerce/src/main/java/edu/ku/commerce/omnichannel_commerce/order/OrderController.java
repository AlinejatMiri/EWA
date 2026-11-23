package edu.ku.commerce.omnichannel_commerce.order;

import edu.ku.commerce.omnichannel_commerce.common.domain.AppUser;
import edu.ku.commerce.omnichannel_commerce.common.domain.AppUserRepository;
import edu.ku.commerce.omnichannel_commerce.common.domain.Role;
import edu.ku.commerce.omnichannel_commerce.payment.Payment;
import edu.ku.commerce.omnichannel_commerce.payment.PaymentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final PurchaseOrderRepository orders;
    private final OrderItemRepository orderItems;
    private final OrderStatusHistoryRepository statusHistory;
    private final ReturnRequestRepository returnRequests;
    private final PaymentRepository payments;
    private final AppUserRepository users;

    public OrderController(
            PurchaseOrderRepository orders,
            OrderItemRepository orderItems,
            OrderStatusHistoryRepository statusHistory,
            ReturnRequestRepository returnRequests,
            PaymentRepository payments,
            AppUserRepository users) {
        this.orders = orders;
        this.orderItems = orderItems;
        this.statusHistory = statusHistory;
        this.returnRequests = returnRequests;
        this.payments = payments;
        this.users = users;
    }

    public record CustomerView(String name, String email, String phone, String address) {}

    public record ItemView(Long productId, String name, BigDecimal unitPrice, int quantity) {}

    public record PaymentView(String paymentMethod, String status, BigDecimal amount) {}

    public record BillView(String invoiceNumber, Instant issuedAt, String status) {}

    public record StatusView(String status, Instant changedAt, String changedBy) {}

    public record OrderView(
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

    public record PageView<T>(List<T> content, int page, int size, long totalElements, int totalPages) {}

    public record ReturnView(Long id, Long orderId, String status, Instant requestedAt) {}

    @GetMapping
    @Transactional(readOnly = true)
    public PageView<OrderView> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "createdAt,desc") String sort,
            Authentication authentication) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Page must be non-negative and size must be between 1 and 100");
        }
        Pageable pageable = PageRequest.of(page, size, orderSort(sort));
        AppUser user = currentUser(authentication);
        boolean staff = user.getRole() == Role.ADMIN || user.getRole() == Role.STAFF;
        OrderStatus requestedStatus = status == null || status.isBlank() ? null : parseStatus(status);
        Page<PurchaseOrder> result;
        if (staff) {
            result = requestedStatus == null ? orders.findAll(pageable) : orders.findByStatus(requestedStatus, pageable);
        } else {
            result =
                    requestedStatus == null
                            ? orders.findByCustomerId(user.getId(), pageable)
                            : orders.findByCustomerIdAndStatus(user.getId(), requestedStatus, pageable);
        }
        return new PageView<>(
                result.getContent().stream().map(this::view).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<OrderView> get(
            @PathVariable Long id, @RequestHeader(value = "If-None-Match", required = false) String none, Authentication authentication) {
        PurchaseOrder order = ownedOrder(id, authentication);
        String tag = etag(order);
        if (tag.equals(none)) {
            return ResponseEntity.status(304).eTag(tag).build();
        }
        return ResponseEntity.ok().eTag(tag).body(view(order));
    }

    @PostMapping("/{id}/returns")
    @Transactional
    public ResponseEntity<ReturnView> requestReturn(
            @PathVariable Long id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            Authentication authentication) {
        PurchaseOrder order = ownedOrder(id, authentication);
        requireVersion(order, ifMatch);
        if (order.getStatus() != OrderStatus.CONFIRMED
                && order.getStatus() != OrderStatus.PICKING
                && order.getStatus() != OrderStatus.PACKED
                && order.getStatus() != OrderStatus.SHIPPED
                && order.getStatus() != OrderStatus.DELIVERED) {
            throw new IllegalStateException("This order is not eligible for a return request");
        }
        if (returnRequests.findFirstByOrderIdOrderByRequestedAtDesc(id)
                .filter(request -> "REQUESTED".equals(request.getStatus()))
                .isPresent()) {
            throw new IllegalStateException("A return request is already pending for this order");
        }
        AppUser user = currentUser(authentication);
        ReturnRequest request = new ReturnRequest();
        request.setOrderId(id);
        request.setRequestedBy(user.getEmail());
        request = returnRequests.save(request);
        order.setStatus(OrderStatus.RETURN_REQUESTED);
        order = orders.saveAndFlush(order);
        addHistory(order, user.getEmail());
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(etag(order))
                .body(new ReturnView(request.getId(), id, request.getStatus(), request.getRequestedAt()));
    }

    @PostMapping("/{id}/fulfill")
    @Transactional
    public ResponseEntity<OrderView> fulfill(
            @PathVariable Long id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            Authentication authentication) {
        requireStaff(authentication);
        PurchaseOrder order = findOrder(id);
        requireVersion(order, ifMatch);
        OrderStatus next =
                switch (order.getStatus()) {
                    case CONFIRMED -> OrderStatus.PICKING;
                    case PICKING -> OrderStatus.PACKED;
                    case PACKED -> "BRANCH".equals(order.getChannel()) ? OrderStatus.READY_FOR_PICKUP : OrderStatus.SHIPPED;
                    case SHIPPED, READY_FOR_PICKUP -> OrderStatus.DELIVERED;
                    default -> throw new IllegalStateException("Order cannot be fulfilled from status " + order.getStatus());
                };
        order.setStatus(next);
        order = orders.saveAndFlush(order);
        addHistory(order, currentUser(authentication).getEmail());
        return ResponseEntity.ok().eTag(etag(order)).body(view(order));
    }

    @PostMapping("/{id}/returns/approve")
    @Transactional
    public ResponseEntity<OrderView> approveReturn(
            @PathVariable Long id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            Authentication authentication) {
        requireStaff(authentication);
        PurchaseOrder order = findOrder(id);
        requireVersion(order, ifMatch);
        if (order.getStatus() != OrderStatus.RETURN_REQUESTED) {
            throw new IllegalStateException("Order has no pending return request");
        }
        ReturnRequest request =
                returnRequests.findFirstByOrderIdOrderByRequestedAtDesc(id)
                        .filter(existing -> "REQUESTED".equals(existing.getStatus()))
                        .orElseThrow(() -> new IllegalStateException("Return request was not found"));
        request.setStatus("APPROVED");
        request.setResolvedAt(Instant.now());
        order.setStatus(OrderStatus.RETURNED);
        order = orders.saveAndFlush(order);
        payments.findByOrderId(id).ifPresent(payment -> payment.setStatus(Payment.Status.REFUNDED));
        addHistory(order, currentUser(authentication).getEmail());
        return ResponseEntity.ok().eTag(etag(order)).body(view(order));
    }

    private OrderView view(PurchaseOrder order) {
        Payment payment = payments.findByOrderId(order.getId()).orElse(null);
        String returnStatus =
                returnRequests.findFirstByOrderIdOrderByRequestedAtDesc(order.getId())
                        .map(ReturnRequest::getStatus)
                        .orElse(null);
        List<StatusView> history =
                statusHistory.findByOrderIdOrderByChangedAtAsc(order.getId()).stream()
                        .map(entry -> new StatusView(entry.getStatus().name(), entry.getChangedAt(), entry.getChangedBy()))
                        .toList();
        return new OrderView(
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
                orderItems.findByOrderId(order.getId()).stream()
                        .map(item -> new ItemView(item.getProductId(), item.getName(), item.getUnitPrice(), item.getQuantity()))
                        .toList(),
                payment == null
                        ? null
                        : new PaymentView(order.getPaymentMethod(), payment.getStatus().name(), payment.getAmount()),
                new BillView("INV-" + order.getId(), order.getCreatedAt(), order.getStatus().name()),
                history,
                returnStatus,
                order.getVersion());
    }

    private PurchaseOrder ownedOrder(Long id, Authentication authentication) {
        PurchaseOrder order = findOrder(id);
        AppUser user = currentUser(authentication);
        if (user.getRole() != Role.ADMIN
                && user.getRole() != Role.STAFF
                && !order.getCustomerId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Order belongs to another user");
        }
        return order;
    }

    private PurchaseOrder findOrder(Long id) {
        return orders.findById(id).orElseThrow(() -> new IllegalArgumentException("Order not found"));
    }

    private AppUser currentUser(Authentication authentication) {
        return users.findByEmail(authentication.getName()).orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    private void requireStaff(Authentication authentication) {
        Role role = currentUser(authentication).getRole();
        if (role != Role.ADMIN && role != Role.STAFF) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Staff access is required");
        }
    }

    private void addHistory(PurchaseOrder order, String changedBy) {
        OrderStatusHistory history = new OrderStatusHistory();
        history.setOrderId(order.getId());
        history.setStatus(order.getStatus());
        history.setChangedBy(changedBy);
        statusHistory.save(history);
    }

    private OrderStatus parseStatus(String status) {
        try {
            return OrderStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown order status: " + status);
        }
    }

    private Sort orderSort(String sort) {
        String[] parts = sort.split(",", 2);
        if (!parts[0].equals("createdAt") && !parts[0].equals("total") && !parts[0].equals("status")) {
            throw new IllegalArgumentException("Unsupported order sort field: " + parts[0]);
        }
        Sort.Direction direction = parts.length == 2 ? Sort.Direction.fromString(parts[1]) : Sort.Direction.DESC;
        return Sort.by(direction, parts[0]);
    }

    private String etag(PurchaseOrder order) {
        return "\"" + order.getVersion() + "\"";
    }

    private void requireVersion(PurchaseOrder order, String ifMatch) {
        if (ifMatch != null && !ifMatch.equals(etag(order))) {
            throw new ResponseStatusException(
                    HttpStatus.PRECONDITION_FAILED, "The order changed; reload it and retry");
        }
    }
}
