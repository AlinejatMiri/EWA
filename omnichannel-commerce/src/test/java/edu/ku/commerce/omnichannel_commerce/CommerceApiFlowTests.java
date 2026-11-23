package edu.ku.commerce.omnichannel_commerce;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.ku.commerce.omnichannel_commerce.catalog.ProductRepository;
import edu.ku.commerce.omnichannel_commerce.common.domain.AppUserRepository;
import edu.ku.commerce.omnichannel_commerce.inventory.Location;
import edu.ku.commerce.omnichannel_commerce.inventory.StockRepository;
import edu.ku.commerce.omnichannel_commerce.order.OrderStatus;
import edu.ku.commerce.omnichannel_commerce.order.PurchaseOrderRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class CommerceApiFlowTests {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;
    @Autowired private ProductRepository products;
    @Autowired private StockRepository stocks;
    @Autowired private PurchaseOrderRepository orders;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void customerCheckoutIsIdempotentAndSupportsReturnsAndFulfillment() throws Exception {
        var seededCustomer = users.findByEmail("customer@example.com").orElseThrow();
        org.junit.jupiter.api.Assertions.assertTrue(
                passwordEncoder.matches("password", seededCustomer.getPassword()));
        String customerToken = login("customer@example.com");
        String adminToken = login("admin@example.com");

        mockMvc.perform(get("/api/products?page=0&size=100&sort=name,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.content[0].image_url").isNotEmpty());

        mockMvc.perform(
                        post("/api/admin/promotions")
                                .header("Authorization", bearer(customerToken))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"code":"NOPE","kind":"FIXED","value":2.00,"startsAt":"2026-01-01T00:00:00Z","endsAt":"2027-01-01T00:00:00Z","usageLimit":10}
                                        """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").isNotEmpty());

        MvcResult cartResult =
                mockMvc.perform(
                                post("/api/carts")
                                        .header("Authorization", bearer(customerToken)))
                        .andExpect(status().isCreated())
                        .andReturn();
        JsonNode cart = json.readTree(cartResult.getResponse().getContentAsString());
        long cartId = cart.get("id").asLong();
        String cartEtag = cartResult.getResponse().getHeader("ETag");
        long productId = products.findBySkuIgnoreCase("SKU-001").orElseThrow().getId();
        mockMvc.perform(
                        post("/api/carts/{id}/items", cartId)
                                .header("Authorization", bearer(customerToken))
                                .header("If-Match", cartEtag)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"productId\":" + productId + ",\"quantity\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(1));

        var product = products.findBySkuIgnoreCase("SKU-001").orElseThrow();
        var stock = stocks.findByProductIdAndLocation(product.getId(), Location.WEB).orElseThrow();
        int availableBeforeFailure = stock.getAvailable();
        int orderCountBefore = orders.findAll().size();
        String failedKey = UUID.randomUUID().toString();
        String failedBody = checkoutBody(cartId, "4000000000000002");
        mockMvc.perform(
                        post("/api/checkout")
                                .header("Authorization", bearer(customerToken))
                                .header("Idempotency-Key", failedKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(failedBody))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.message").value("Payment was declined"))
                .andExpect(jsonPath("$.path").value("/api/checkout"));
        org.junit.jupiter.api.Assertions.assertEquals(
                availableBeforeFailure,
                stocks.findByProductIdAndLocation(product.getId(), Location.WEB).orElseThrow().getAvailable());
        org.junit.jupiter.api.Assertions.assertEquals(orderCountBefore + 1, orders.findAll().size());
        org.junit.jupiter.api.Assertions.assertTrue(
                orders.findAll().stream().anyMatch(order -> order.getStatus() == OrderStatus.FAILED));

        mockMvc.perform(
                        post("/api/checkout")
                                .header("Authorization", bearer(customerToken))
                                .header("Idempotency-Key", failedKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(failedBody))
                .andExpect(status().isPaymentRequired());
        org.junit.jupiter.api.Assertions.assertEquals(orderCountBefore + 1, orders.findAll().size());

        String checkoutKey = UUID.randomUUID().toString();
        String successBody = checkoutBody(cartId, "4242424242424242");
        MvcResult checkoutResult =
                mockMvc.perform(
                                post("/api/checkout")
                                        .header("Authorization", bearer(customerToken))
                                        .header("Idempotency-Key", checkoutKey)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(successBody))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.items[0].quantity").value(1))
                        .andExpect(jsonPath("$.bill.invoiceNumber").isNotEmpty())
                        .andReturn();
        JsonNode order = json.readTree(checkoutResult.getResponse().getContentAsString());
        long orderId = order.get("id").asLong();
        mockMvc.perform(
                        post("/api/checkout")
                                .header("Authorization", bearer(customerToken))
                                .header("Idempotency-Key", checkoutKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(successBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(orderId));
        org.junit.jupiter.api.Assertions.assertEquals(orderCountBefore + 2, orders.findAll().size());

        MvcResult orderDetails =
                mockMvc.perform(
                                get("/api/orders/{id}", orderId)
                                        .header("Authorization", bearer(adminToken)))
                        .andExpect(status().isOk())
                        .andReturn();
        mockMvc.perform(
                        post("/api/orders/{id}/fulfill", orderId)
                                .header("Authorization", bearer(adminToken))
                                .header("If-Match", orderDetails.getResponse().getHeader("ETag")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PICKING"));

        mockMvc.perform(
                        post("/api/orders/{id}/returns", orderId)
                                .header("Authorization", bearer(customerToken)))
                .andExpect(status().isCreated());
        MvcResult requestedOrder =
                mockMvc.perform(
                                get("/api/orders/{id}", orderId)
                                        .header("Authorization", bearer(adminToken)))
                        .andReturn();
        mockMvc.perform(
                        post("/api/orders/{id}/returns/approve", orderId)
                                .header("Authorization", bearer(adminToken))
                                .header("If-Match", requestedOrder.getResponse().getHeader("ETag")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETURNED"))
                .andExpect(jsonPath("$.payment.status").value("REFUNDED"));
    }

    private String login(String email) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\""
                                                + email
                                                + "\",\"password\":\"password\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.token").isNotEmpty())
                        .andExpect(jsonPath("$.user.role").isNotEmpty())
                        .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String checkoutBody(long cartId, String cardNumber) {
        return """
                {"cartId":%d,"paymentMethod":"CREDIT_CARD","name":"Test Customer","email":"customer@example.com","phone":"555-0100","address":"1 Test Street","cardNumber":"%s","cardExpiry":"12/30","cardCvc":"123"}
                """
                .formatted(cartId, cardNumber);
    }
}
