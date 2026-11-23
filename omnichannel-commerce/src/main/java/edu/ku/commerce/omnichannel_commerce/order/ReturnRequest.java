package edu.ku.commerce.omnichannel_commerce.order;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "return_request")
@Getter
@Setter
@NoArgsConstructor
public class ReturnRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long orderId;

    private String requestedBy;

    private String status = "REQUESTED";

    private Instant requestedAt = Instant.now();

    private Instant resolvedAt;
}
