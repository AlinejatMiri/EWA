package edu.ku.commerce.omnichannel_commerce.inventory;
import jakarta.persistence.*; import java.time.*; import lombok.*;
@Entity @Getter @Setter @NoArgsConstructor public class Reservation { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; private Long orderId; private Long productId; @Enumerated(EnumType.STRING) private Location location; private int quantity; private Instant expiresAt; @Enumerated(EnumType.STRING) private State state=State.ACTIVE; public enum State { ACTIVE, COMMITTED, RELEASED } }
