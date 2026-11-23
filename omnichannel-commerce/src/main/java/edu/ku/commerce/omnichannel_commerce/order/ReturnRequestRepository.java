package edu.ku.commerce.omnichannel_commerce.order;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReturnRequestRepository extends JpaRepository<ReturnRequest, Long> {
    Optional<ReturnRequest> findFirstByOrderIdOrderByRequestedAtDesc(Long orderId);
}
