package edu.ku.commerce.omnichannel_commerce.cart; import jakarta.persistence.*; import lombok.*;
@Entity @Getter @Setter @NoArgsConstructor public class CartItem { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="cart_id") private Cart cart; private Long productId; private int quantity; }
