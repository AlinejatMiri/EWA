package edu.ku.commerce.omnichannel_commerce.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {
    Page<PurchaseOrder> findByCustomerId(Long customerId, Pageable pageable);

    Page<PurchaseOrder> findByStatus(OrderStatus status, Pageable pageable);

    Page<PurchaseOrder> findByCustomerIdAndStatus(Long customerId, OrderStatus status, Pageable pageable);
}
