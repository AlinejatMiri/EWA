package edu.ku.commerce.omnichannel_commerce.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/products")
@Validated
public class CatalogController {
    private static final long MAX_IMAGE_SIZE = 5_000_000;

    private final ProductRepository products;
    private final ObjectMapper json;

    public CatalogController(ProductRepository products, ObjectMapper json) {
        this.products = products;
        this.json = json;
    }

    public record ProductRequest(
            @NotBlank String sku,
            @NotBlank String name,
            String category,
            @NotNull @PositiveOrZero BigDecimal price,
            String image_url,
            String description,
            Map<String, String> specs) {}

    public record ProductView(
            Long id,
            String sku,
            String name,
            String category,
            BigDecimal price,
            String image_url,
            String description,
            Map<String, String> specs,
            boolean active,
            Long version) {}

    public record ImageView(String image_url) {}

    public record PageView<T>(List<T> content, int page, int size, long totalElements, int totalPages) {}

    private ProductView view(Product product) {
        try {
            Map<String, String> specs =
                    product.getSpecs() == null
                            ? Map.of()
                            : json.readValue(product.getSpecs(), new TypeReference<>() {});
            return new ProductView(
                    product.getId(),
                    product.getSku(),
                    product.getName(),
                    product.getCategory(),
                    product.getPrice(),
                    product.getImageUrl(),
                    product.getDescription(),
                    specs,
                    product.isActive(),
                    product.getVersion());
        } catch (IOException e) {
            throw new IllegalStateException("Product specifications could not be read");
        }
    }

    private void apply(Product product, ProductRequest request) {
        product.setSku(request.sku().trim());
        product.setName(request.name().trim());
        product.setCategory(request.category());
        product.setPrice(request.price());
        product.setImageUrl(request.image_url());
        product.setDescription(request.description());
        try {
            product.setSpecs(json.writeValueAsString(request.specs() == null ? Map.of() : request.specs()));
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid product specifications");
        }
    }

    @GetMapping
    public PageView<ProductView> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Page must be non-negative and size must be between 1 and 100");
        }
        List<Product> filtered =
                products.findByActiveTrue().stream()
                        .filter(p -> category == null || category.isBlank() || category.equalsIgnoreCase(p.getCategory()))
                        .filter(
                                p ->
                                        search == null
                                                || search.isBlank()
                                                || p.getName().toLowerCase().contains(search.toLowerCase())
                                                || p.getSku().toLowerCase().contains(search.toLowerCase()))
                        .sorted(productComparator(sort))
                        .toList();
        int from = Math.min(page * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        return new PageView<>(
                filtered.subList(from, to).stream().map(this::view).toList(),
                page,
                size,
                filtered.size(),
                (int) Math.ceil(filtered.size() / (double) size));
    }

    private Comparator<Product> productComparator(String sort) {
        if (sort == null || sort.isBlank()) {
            return Comparator.comparing(Product::getId);
        }
        String[] parts = sort.split(",", 2);
        String property = parts[0].trim();
        Comparator<Product> comparator =
                switch (property) {
                    case "name" -> Comparator.comparing(Product::getName, String.CASE_INSENSITIVE_ORDER);
                    case "price" -> Comparator.comparing(Product::getPrice);
                    case "category" -> Comparator.comparing(Product::getCategory, Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER));
                    case "sku" -> Comparator.comparing(Product::getSku, String.CASE_INSENSITIVE_ORDER);
                    default -> throw new IllegalArgumentException("Unsupported product sort field: " + property);
                };
        if (parts.length == 2 && "desc".equalsIgnoreCase(parts[1].trim())) {
            return comparator.reversed();
        }
        if (parts.length == 2 && !"asc".equalsIgnoreCase(parts[1].trim())) {
            throw new IllegalArgumentException("Sort direction must be asc or desc");
        }
        return comparator;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductView> get(
            @PathVariable Long id, @RequestHeader(value = "If-None-Match", required = false) String none) {
        Product product = findProduct(id);
        String tag = etag(product);
        if (tag.equals(none)) {
            return ResponseEntity.status(304).eTag(tag).build();
        }
        return ResponseEntity.ok().eTag(tag).body(view(product));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductView create(@Valid @RequestBody ProductRequest request) {
        Product product = new Product();
        apply(product, request);
        return view(products.save(product));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProductView> update(
            @PathVariable Long id,
            @Valid @RequestBody ProductRequest request,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        Product product = findProduct(id);
        requireVersion(product.getVersion(), ifMatch);
        apply(product, request);
        Product saved = products.saveAndFlush(product);
        return ResponseEntity.ok().eTag(etag(saved)).body(view(saved));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(
            @PathVariable Long id, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        Product product = findProduct(id);
        requireVersion(product.getVersion(), ifMatch);
        product.setActive(false);
        products.save(product);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ImageView> image(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "If-Match", required = false) String ifMatch)
            throws IOException {
        if (file.isEmpty() || file.getSize() > MAX_IMAGE_SIZE) {
            throw new IllegalArgumentException("Image must be non-empty and no more than 5 MB");
        }
        String extension =
                switch (StringUtils.getFilenameExtension(file.getOriginalFilename()) == null
                        ? ""
                        : StringUtils.getFilenameExtension(file.getOriginalFilename()).toLowerCase()) {
                    case "jpg", "jpeg" -> "jpg";
                    case "png" -> "png";
                    case "webp" -> "webp";
                    default -> throw new IllegalArgumentException("Only JPEG, PNG, and WebP images are supported");
                };
        if (file.getContentType() == null
                || !List.of("image/jpeg", "image/png", "image/webp").contains(file.getContentType())) {
            throw new IllegalArgumentException("Only JPEG, PNG, and WebP images are supported");
        }
        Product product = findProduct(id);
        requireVersion(product.getVersion(), ifMatch);
        Path directory = Paths.get("uploads").toAbsolutePath().normalize();
        Files.createDirectories(directory);
        String filename = UUID.randomUUID() + "." + extension;
        Files.copy(file.getInputStream(), directory.resolve(filename));
        String imageUrl =
                ServletUriComponentsBuilder.fromCurrentContextPath()
                        .path("/uploads/")
                        .path(filename)
                        .toUriString();
        product.setImageUrl(imageUrl);
        products.saveAndFlush(product);
        return ResponseEntity.ok().eTag(etag(product)).body(new ImageView(imageUrl));
    }

    private Product findProduct(Long id) {
        return products.findById(id).filter(Product::isActive).orElseThrow(() -> new IllegalArgumentException("Product not found"));
    }

    private String etag(Product product) {
        return "\"" + product.getVersion() + "\"";
    }

    private void requireVersion(Long version, String ifMatch) {
        if (ifMatch != null && !ifMatch.equals("\"" + version + "\"")) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.PRECONDITION_FAILED, "The resource changed; reload it and retry");
        }
    }
}
