package edu.ku.commerce.omnichannel_commerce.inventory;
import jakarta.persistence.*; import lombok.*;
@Entity @Table(uniqueConstraints=@UniqueConstraint(columnNames={"product_id","location"})) @Getter @Setter @NoArgsConstructor public class Stock { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; @Column(name="product_id",nullable=false) private Long productId; @Enumerated(EnumType.STRING) private Location location; private int available; private int reserved; @Version private Long version; }
