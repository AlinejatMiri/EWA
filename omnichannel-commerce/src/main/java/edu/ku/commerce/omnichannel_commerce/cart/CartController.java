package edu.ku.commerce.omnichannel_commerce.cart;

import edu.ku.commerce.omnichannel_commerce.catalog.Promotion;
import edu.ku.commerce.omnichannel_commerce.catalog.PromotionRepository;
import edu.ku.commerce.omnichannel_commerce.common.domain.AppUser;
import edu.ku.commerce.omnichannel_commerce.common.domain.AppUserRepository;
import edu.ku.commerce.omnichannel_commerce.common.domain.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/carts")
@Validated
public class CartController {
    private final CartRepository carts;
    private final AppUserRepository users;
    private final PromotionRepository promotions;

    public CartController(CartRepository carts, AppUserRepository users, PromotionRepository promotions) {
        this.carts = carts;
        this.users = users;
        this.promotions = promotions;
    }

    public record CartItemRequest(@NotNull Long productId, @Min(1) int quantity) {}

    public record Item(Long productId, int quantity) {}

    public record View(Long id, String channel, Long version, List<Item> items, String promoCode) {}

    private View view(Cart cart) {
        return new View(
                cart.getId(),
                cart.getChannel(),
                cart.getVersion(),
                cart.getItems().stream().map(item -> new Item(item.getProductId(), item.getQuantity())).toList(),
                cart.getPromoCode());
    }

    private AppUser user(Authentication authentication) {
        return users.findByEmail(authentication.getName()).orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    private Cart cart(Long id, Authentication authentication) {
        Cart cart = carts.findById(id).orElseThrow(() -> new IllegalArgumentException("Cart not found"));
        AppUser user = user(authentication);
        if (!cart.getCustomerId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cart belongs to another user");
        }
        if ("BRANCH".equals(cart.getChannel()) && user.getRole() == Role.CUSTOMER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Branch sales require staff access");
        }
        return cart;
    }

    private ResponseEntity<View> response(Cart cart) {
        Cart saved = carts.saveAndFlush(cart);
        return ResponseEntity.ok().eTag(etag(saved)).body(view(saved));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<View> create(
            @RequestParam(defaultValue = "WEB") String channel, Authentication authentication) {
        AppUser user = user(authentication);
        String normalized = channel.toUpperCase();
        if (!normalized.equals("WEB") && !normalized.equals("BRANCH")) {
            throw new IllegalArgumentException("Cart channel must be WEB or BRANCH");
        }
        if (normalized.equals("BRANCH") && user.getRole() == Role.CUSTOMER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Branch sales require staff access");
        }
        Cart cart =
                carts.findByCustomerIdAndChannel(user.getId(), normalized)
                        .orElseGet(
                                () -> {
                                    Cart created = new Cart();
                                    created.setCustomerId(user.getId());
                                    created.setChannel(normalized);
                                    return created;
                                });
        Cart saved = carts.saveAndFlush(cart);
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etag(saved)).body(view(saved));
    }

    @GetMapping
    public ResponseEntity<View> active(
            @RequestParam(defaultValue = "WEB") String channel, Authentication authentication) {
        AppUser user = user(authentication);
        String normalized = channel.toUpperCase();
        if (!normalized.equals("WEB") && !normalized.equals("BRANCH")) {
            throw new IllegalArgumentException("Cart channel must be WEB or BRANCH");
        }
        if (normalized.equals("BRANCH") && user.getRole() == Role.CUSTOMER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Branch sales require staff access");
        }
        Cart cart =
                carts.findByCustomerIdAndChannel(user.getId(), normalized)
                        .orElseGet(
                                () -> {
                                    Cart created = new Cart();
                                    created.setCustomerId(user.getId());
                                    created.setChannel(normalized);
                                    return carts.save(created);
                                });
        return ResponseEntity.ok().eTag(etag(cart)).body(view(cart));
    }

    @GetMapping("/{id}")
    public ResponseEntity<View> get(@PathVariable Long id, Authentication authentication) {
        Cart cart = cart(id, authentication);
        return ResponseEntity.ok().eTag(etag(cart)).body(view(cart));
    }

    @PostMapping("/{id}/items")
    public ResponseEntity<View> add(
            @PathVariable Long id,
            @Valid @RequestBody CartItemRequest request,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            Authentication authentication) {
        Cart cart = cart(id, authentication);
        requireVersion(cart, ifMatch);
        CartItem item =
                cart.getItems().stream()
                        .filter(existing -> existing.getProductId().equals(request.productId()))
                        .findFirst()
                        .orElseGet(
                                () -> {
                                    CartItem created = new CartItem();
                                    created.setCart(cart);
                                    created.setProductId(request.productId());
                                    cart.getItems().add(created);
                                    return created;
                                });
        item.setQuantity(item.getQuantity() + request.quantity());
        return response(cart);
    }

    @PutMapping("/{id}/items/{productId}")
    public ResponseEntity<View> update(
            @PathVariable Long id,
            @PathVariable Long productId,
            @RequestParam @Min(1) int quantity,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            Authentication authentication) {
        Cart cart = cart(id, authentication);
        requireVersion(cart, ifMatch);
        CartItem item =
                cart.getItems().stream()
                        .filter(existing -> existing.getProductId().equals(productId))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Cart item not found"));
        item.setQuantity(quantity);
        return response(cart);
    }

    @DeleteMapping("/{id}/items/{productId}")
    public ResponseEntity<View> remove(
            @PathVariable Long id,
            @PathVariable Long productId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            Authentication authentication) {
        Cart cart = cart(id, authentication);
        requireVersion(cart, ifMatch);
        if (!cart.getItems().removeIf(item -> item.getProductId().equals(productId))) {
            throw new IllegalArgumentException("Cart item not found");
        }
        return response(cart);
    }

    @PutMapping("/{id}/promo")
    public ResponseEntity<View> applyPromotion(
            @PathVariable Long id,
            @RequestParam String code,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            Authentication authentication) {
        Cart cart = cart(id, authentication);
        requireVersion(cart, ifMatch);
        Promotion promotion =
                promotions.findByCodeIgnoreCase(code.trim())
                        .orElseThrow(() -> new IllegalArgumentException("Promotion code is invalid"));
        Instant now = Instant.now();
        if (now.isBefore(promotion.getStartsAt())
                || now.isAfter(promotion.getEndsAt())
                || promotion.getUsageCount() >= promotion.getUsageLimit()) {
            throw new IllegalStateException("Promotion is expired or has reached its usage limit");
        }
        cart.setPromoCode(promotion.getCode());
        return response(cart);
    }

    private String etag(Cart cart) {
        return "\"" + cart.getVersion() + "\"";
    }

    private void requireVersion(Cart cart, String ifMatch) {
        if (ifMatch != null && !ifMatch.equals(etag(cart))) {
            throw new ResponseStatusException(
                    HttpStatus.PRECONDITION_FAILED, "The cart changed; reload it and retry");
        }
    }
}
