package edu.ku.commerce.omnichannel_commerce.order;
import jakarta.persistence.*; import lombok.*; import java.math.*;
@Entity @Table(name="order_item") @Getter @Setter @NoArgsConstructor public class OrderItem { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; private Long orderId; private Long productId; private String name; @Column(precision=19,scale=2) private BigDecimal unitPrice; private int quantity; }
