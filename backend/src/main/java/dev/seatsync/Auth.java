package dev.seatsync;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

record SignupRequest(@NotBlank @Size(min=2,max=80) String username,
                     @NotBlank @Email @Size(max=254) String email,
                     @NotBlank @Size(min=8,max=72) String password,
                     @NotBlank String confirmPassword,
                     @NotBlank @Pattern(regexp="[+0-9() -]{7,24}") String mobile) {}
record LoginRequest(@NotBlank @Email String email,@NotBlank String password) {}
record UserDto(UUID id,String username,String email,String mobile) {}
record AuthResponse(String token,UserDto user) {}

@Service
class JwtService {
    private static final String HEADER=Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    private final byte[] secret; private final long ttlSeconds;
    JwtService(@Value("${seatsync.jwt.secret}") String secret,@Value("${seatsync.jwt.ttl-hours:12}") long ttlHours) {
        if(secret.getBytes(StandardCharsets.UTF_8).length<32) throw new IllegalArgumentException("JWT secret must be at least 32 bytes");
        this.secret=secret.getBytes(StandardCharsets.UTF_8); this.ttlSeconds=ttlHours*3600;
    }
    String issue(String email) {
        long exp=Instant.now().getEpochSecond()+ttlSeconds;
        String payload=Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"sub\":\""+email+"\",\"exp\":"+exp+"}").getBytes(StandardCharsets.UTF_8));
        String body=HEADER+"."+payload;
        return body+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(sign(body));
    }
    Optional<String> subject(String token) {
        try {
            String[] parts=token.split("\\."); if(parts.length!=3) return Optional.empty();
            byte[] provided=Base64.getUrlDecoder().decode(parts[2]); byte[] expected=sign(parts[0]+"."+parts[1]);
            if(!MessageDigest.isEqual(provided,expected)) return Optional.empty();
            String json=new String(Base64.getUrlDecoder().decode(parts[1]),StandardCharsets.UTF_8);
            var sub=java.util.regex.Pattern.compile("\\\"sub\\\":\\\"([^\\\"]+)\\\"").matcher(json);
            var exp=java.util.regex.Pattern.compile("\\\"exp\\\":(\\d+)").matcher(json);
            if(!sub.find()||!exp.find()||Long.parseLong(exp.group(1))<Instant.now().getEpochSecond()) return Optional.empty();
            return Optional.of(sub.group(1));
        } catch(Exception ex) { return Optional.empty(); }
    }
    private byte[] sign(String value) {
        try { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret,"HmacSHA256")); return mac.doFinal(value.getBytes(StandardCharsets.UTF_8)); }
        catch(Exception ex) { throw new IllegalStateException(ex); }
    }
}

class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    JwtAuthFilter(JwtService jwt) { this.jwt=jwt; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String header=request.getHeader("Authorization");
        if(header!=null&&header.startsWith("Bearer ")) jwt.subject(header.substring(7)).ifPresent(email -> {
            var auth=new UsernamePasswordAuthenticationToken(email,null,List.of(new SimpleGrantedAuthority("ROLE_USER")));
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        });
        chain.doFilter(request,response);
    }
}

@Configuration
class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean JwtAuthFilter jwtAuthFilter(JwtService jwt) { return new JwtAuthFilter(jwt); }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http,JwtAuthFilter jwt) throws Exception {
        return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a->a.requestMatchers("/api/v1/auth/**","/v3/api-docs/**","/swagger-ui/**","/swagger-ui.html","/actuator/health")
                        .permitAll().requestMatchers(HttpMethod.GET,"/api/v1/areas","/api/v1/theatres/**","/api/v1/shows/**").permitAll().anyRequest().authenticated())
                .addFilterBefore(jwt,UsernamePasswordAuthenticationFilter.class).build();
    }
}

@Service
class AuthService {
    private final UserRepository users; private final PasswordEncoder encoder; private final JwtService jwt;
    AuthService(UserRepository users,PasswordEncoder encoder,JwtService jwt) { this.users=users;this.encoder=encoder;this.jwt=jwt; }
    AuthResponse signup(SignupRequest r) {
        if(!r.password().equals(r.confirmPassword())) throw new IllegalArgumentException("Password confirmation does not match");
        String email=r.email().trim().toLowerCase(Locale.ROOT);
        if(users.existsByEmailIgnoreCase(email)) throw new IllegalStateException("An account with this email already exists");
        if(users.existsByUsernameIgnoreCase(r.username().trim())) throw new IllegalStateException("That username is already taken");
        var user=users.save(new UserEntity(UUID.randomUUID(),r.username().trim(),email,encoder.encode(r.password()),r.mobile().trim()));
        return response(user);
    }
    AuthResponse login(LoginRequest r) {
        var user=users.findByEmailIgnoreCase(r.email().trim()).orElseThrow(()->new IllegalArgumentException("Email or password is incorrect"));
        if(!encoder.matches(r.password(),user.passwordHash)) throw new IllegalArgumentException("Email or password is incorrect");
        return response(user);
    }
    private AuthResponse response(UserEntity user) { return new AuthResponse(jwt.issue(user.email),new UserDto(user.id,user.username,user.email,user.mobile)); }
}

@RestController @RequestMapping("/api/v1/auth")
class AuthController {
    private final AuthService auth;
    AuthController(AuthService auth) { this.auth=auth; }
    @PostMapping("/signup") AuthResponse signup(@Valid @RequestBody SignupRequest request) { return auth.signup(request); }
    @PostMapping("/login") AuthResponse login(@Valid @RequestBody LoginRequest request) { return auth.login(request); }
}
