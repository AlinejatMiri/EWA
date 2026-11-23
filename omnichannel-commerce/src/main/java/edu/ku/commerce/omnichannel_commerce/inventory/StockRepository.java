package edu.ku.commerce.omnichannel_commerce.inventory;
import java.util.*; import org.springframework.data.jpa.repository.*; import jakarta.persistence.LockModeType;
public interface StockRepository extends JpaRepository<Stock,Long>{ @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from Stock s where s.productId=:productId and s.location=:location") Optional<Stock> lockByProductIdAndLocation(Long productId, Location location); List<Stock> findByProductId(Long productId); Optional<Stock> findByProductIdAndLocation(Long productId, Location location); }
