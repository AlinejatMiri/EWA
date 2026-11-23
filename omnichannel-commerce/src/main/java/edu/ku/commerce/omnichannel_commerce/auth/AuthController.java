package edu.ku.commerce.omnichannel_commerce.auth;

import edu.ku.commerce.omnichannel_commerce.common.domain.AppUser;
import edu.ku.commerce.omnichannel_commerce.common.domain.AppUserRepository;
import edu.ku.commerce.omnichannel_commerce.common.domain.Role;
import edu.ku.commerce.omnichannel_commerce.common.security.JwtService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthController(AppUserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    public record Credentials(String name, @Email @NotBlank String email, @NotBlank @Size(min = 8) String password) {}

    public record UserView(Long id, String name, String email, String role) {}

    public record Token(String token, UserView user, String accessToken) {}

    private Token token(AppUser user) {
        String value = jwt.issue(user.getEmail(), user.getRole().name());
        UserView userView = new UserView(user.getId(), user.getName(), user.getEmail(), user.getRole().name());
        return new Token(value, userView, value);
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Token register(@Valid @RequestBody Credentials credentials) {
        if (users.existsByEmail(credentials.email())) {
            throw new IllegalStateException("Email already registered");
        }
        AppUser user = new AppUser();
        user.setName(
                credentials.name() == null || credentials.name().isBlank()
                        ? credentials.email()
                        : credentials.name().trim());
        user.setEmail(credentials.email().trim().toLowerCase());
        user.setPassword(encoder.encode(credentials.password()));
        user.setRole(Role.CUSTOMER);
        return token(users.save(user));
    }

    @PostMapping("/login")
    public Token login(@Valid @RequestBody Credentials credentials) {
        AppUser user =
                users.findByEmail(credentials.email().trim().toLowerCase())
                        .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));
        if (!encoder.matches(credentials.password(), user.getPassword())) {
            throw new IllegalArgumentException("Invalid credentials");
        }
        return token(user);
    }

    @GetMapping("/me")
    public UserView me(Authentication authentication) {
        AppUser user =
                users.findByEmail(authentication.getName())
                        .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return new UserView(user.getId(), user.getName(), user.getEmail(), user.getRole().name());
    }
}
