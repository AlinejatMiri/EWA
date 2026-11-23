package edu.ku.commerce.omnichannel_commerce.admin;

import edu.ku.commerce.omnichannel_commerce.catalog.Product;
import edu.ku.commerce.omnichannel_commerce.catalog.ProductRepository;
import edu.ku.commerce.omnichannel_commerce.catalog.Promotion;
import edu.ku.commerce.omnichannel_commerce.catalog.PromotionRepository;
import edu.ku.commerce.omnichannel_commerce.inventory.Location;
import edu.ku.commerce.omnichannel_commerce.inventory.Stock;
import edu.ku.commerce.omnichannel_commerce.inventory.StockRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
@Validated
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {
    private final ProductRepository products;
    private final StockRepository stocks;
    private final PromotionRepository promotions;

    public AdminController(ProductRepository products, StockRepository stocks, PromotionRepository promotions) {
        this.products = products;
        this.stocks = stocks;
        this.promotions = promotions;
    }

    public record StockView(String sku, String location, int available, int reserved) {}

    public record PromotionRequest(
            @NotBlank String code,
            @NotNull Promotion.Kind kind,
            @NotNull @DecimalMin(value = "0.01") BigDecimal value,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            @Positive int usageLimit) {}

    public record PromotionView(
            Long id,
            String code,
            Promotion.Kind kind,
            BigDecimal value,
            Instant startsAt,
            Instant endsAt,
            int usageLimit,
            int usageCount) {}

    @PutMapping("/stock/{sku}")
    @Transactional
    public StockView updateStock(@PathVariable String sku, @RequestParam int available) {
        if (available < 0) {
            throw new IllegalArgumentException("Available stock cannot be negative");
        }
        Product product =
                products.findBySkuIgnoreCase(sku)
                        .orElseThrow(() -> new IllegalArgumentException("Product not found"));
        Stock stock =
                stocks.findByProductIdAndLocation(product.getId(), Location.WEB)
                        .orElseGet(
                                () -> {
                                    Stock created = new Stock();
                                    created.setProductId(product.getId());
                                    created.setLocation(Location.WEB);
                                    return created;
                                });
        if (available < stock.getReserved()) {
            throw new IllegalStateException("Available stock cannot be less than currently reserved stock");
        }
        stock.setAvailable(available);
        Stock saved = stocks.save(stock);
        return new StockView(product.getSku(), saved.getLocation().name(), saved.getAvailable(), saved.getReserved());
    }

    @PostMapping("/promotions")
    @ResponseStatus(HttpStatus.CREATED)
    public PromotionView createPromotion(@Valid @RequestBody PromotionRequest request) {
        if (!request.endsAt().isAfter(request.startsAt())) {
            throw new IllegalArgumentException("Promotion end time must be after its start time");
        }
        if (promotions.findByCodeIgnoreCase(request.code().trim()).isPresent()) {
            throw new IllegalStateException("A promotion with this code already exists");
        }
        Promotion promotion = new Promotion();
        promotion.setCode(request.code().trim().toUpperCase());
        promotion.setKind(request.kind());
        promotion.setValue(request.value());
        promotion.setStartsAt(request.startsAt());
        promotion.setEndsAt(request.endsAt());
        promotion.setUsageLimit(request.usageLimit());
        promotion = promotions.save(promotion);
        return new PromotionView(
                promotion.getId(),
                promotion.getCode(),
                promotion.getKind(),
                promotion.getValue(),
                promotion.getStartsAt(),
                promotion.getEndsAt(),
                promotion.getUsageLimit(),
                promotion.getUsageCount());
    }
}
