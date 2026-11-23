package edu.ku.commerce.omnichannel_commerce.catalog;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "promotion")
@Getter
@Setter
@NoArgsConstructor
public class Promotion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Column(name = "promotion_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal value;

    private Instant startsAt;

    private Instant endsAt;

    private int usageLimit;

    private int usageCount;

    public enum Kind {
        PERCENT,
        FIXED
    }
}
