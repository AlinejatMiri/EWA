package edu.ku.commerce.omnichannel_commerce.common.idempotency; import jakarta.persistence.*; import lombok.*;
@Entity @Getter @Setter @NoArgsConstructor public class IdempotencyRecord { @Id @Column(name="idempotency_key") private String key; @Column(nullable=false) private String requestHash; @Column(nullable=false) private int responseStatus; @Lob private String responseBody; }
