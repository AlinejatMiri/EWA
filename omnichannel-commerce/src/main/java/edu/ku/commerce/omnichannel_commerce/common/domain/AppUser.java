package edu.ku.commerce.omnichannel_commerce.common.domain;
import jakarta.persistence.*; import lombok.*;
@Entity @Table(name="app_users") @Getter @Setter @NoArgsConstructor
public class AppUser { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; private String name; @Column(unique=true,nullable=false) private String email; @Column(nullable=false) private String password; @Enumerated(EnumType.STRING) @Column(nullable=false) private Role role; @Version private Long version; }
