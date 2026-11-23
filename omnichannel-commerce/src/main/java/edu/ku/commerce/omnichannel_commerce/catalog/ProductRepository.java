package edu.ku.commerce.omnichannel_commerce.catalog;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product,Long>{
    List<Product> findByActiveTrue();
    Optional<Product> findBySkuIgnoreCase(String sku);
}
