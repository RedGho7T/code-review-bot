# Security Guidelines

## Input Validation

### Валидируй все входные данные
Ненадежным может быть: HTTP параметры, JSON, файлы, БД.

❌ Плохо:
```java
@PostMapping("/users")
public User createUser(@RequestBody User user) {
    // Не проверяем что user.name не null!
    // Не проверяем что email валиден!
    return userRepository.save(user);
}
```

✅ Хорошо:
```java
@PostMapping("/users")
public User createUser(@RequestBody @Valid User user) {
    // @Valid запускает аннотации @NotNull, @Email и т.д.
    return userRepository.save(user);
}

@Data
public class User {
    @NotNull(message = "Name cannot be null")
    @NotBlank(message = "Name cannot be empty")
    private String name;
    
    @Email(message = "Email should be valid")
    private String email;
}
```

### Используй белый лист, не черный лист
Разрешай только известное, не запрещай неизвестное.

❌ Плохо:
```java
// Запрещаем опасные символы, но что-то можем пропустить
if (!input.contains("<") && !input.contains(">") && ...) {
    process(input);
}
```

✅ Хорошо:
```java
// Разрешаем только буквы и цифры
if (input.matches("[a-zA-Z0-9]+")) {
    process(input);
}
```

## SQL Injection Prevention

### Используй Parameterized Queries
Никогда не конкатенируй строки в SQL.

❌ Плохо (SQL Injection уязвимость!):
```java
String query = "SELECT * FROM users WHERE name = '" + userName + "'";
// Если userName = "admin' OR '1'='1", то вернет всех пользователей!
```

✅ Хорошо:
```java
// JPA защищает автоматически
Optional<User> user = userRepository.findByName(userName);

// Или через @Query с ?1
@Query("SELECT u FROM User u WHERE u.name = ?1")
Optional<User> findByName(String name);
```

## Password Security

### Не храни пароли в открытом виде
Используй bcrypt или другой алгоритм хеширования.

❌ Плохо:
```java
@Entity
public class User {
    private String password;  // Сохраняется как есть!
}
```

✅ Хорошо:
```java
@Entity
public class User {
    private String passwordHash;  // Хеш пароля
}

@Service
public class UserService {
    @Autowired
    private PasswordEncoder passwordEncoder;
    
    public void createUser(String password) {
        // bcrypt автоматически добавляет salt и хеширует
        String hash = passwordEncoder.encode(password);
        user.setPasswordHash(hash);
    }
    
    public boolean checkPassword(String rawPassword, String hash) {
        // Сравнивает безопасно
        return passwordEncoder.matches(rawPassword, hash);
    }
}
```

## Authentication & Authorization

### Используй Spring Security
Не пишись собственную систему аутентификации.

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig extends WebSecurityConfigurerAdapter {
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http
            .authorizeRequests()
            .antMatchers("/public/**").permitAll()
            .antMatchers("/admin/**").hasRole("ADMIN")
            .anyRequest().authenticated()
            .and()
            .httpBasic();
    }
}
```

### Используй @PreAuthorize для методов
Контролируй доступ на уровне методов.

```java
@Service
public class UserService {
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteUser(Long id) {
        // Только ADMIN может вызвать
    }
    
    @PreAuthorize("#userId == authentication.principal.id || hasRole('ADMIN')")
    public User getUser(Long userId) {
        // Пользователь может только получить свои данные или ADMIN
    }
}
```

## API Security

### Используй HTTPS
Никогда не отправляй чувствительные данные по HTTP.

### Добавь API ключи для доступа
Защити endpoints от неавторизованного доступа.

```java
@Configuration
public class ApiKeyConfig {
    @Bean
    public ApiKeyAuthenticationFilter apiKeyAuthenticationFilter() {
        return new ApiKeyAuthenticationFilter();
    }
}

@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                    HttpServletResponse response, 
                                    FilterChain filterChain) {
        String apiKey = request.getHeader("X-API-Key");
        if (isValidApiKey(apiKey)) {
            filterChain.doFilter(request, response);
        } else {
            response.setStatus(401);
        }
    }
}
```

## Logging

### Не логируй чувствительные данные
Пароли, API ключи, кредитные карты и т.д.

❌ Плохо:
```java
log.info("User login: {}", request);  // Может содержать пароль!
log.info("Creating user with email: {} and password: {}", email, password);
```

✅ Хорошо:
```java
log.info("User authentication attempt for: {}", email);
log.debug("User created with role: {}", user.getRole());

// Маскируй чувствительные данные
String maskedCard = card.substring(0, 4) + "****" + card.substring(12);
log.info("Payment processed for card: {}", maskedCard);
```

## Dependency Security

### Обновляй зависимости
Следи за безопасностью уязвимостей в зависимостях.

```gradle
// Используй Gradle dependency check плагин
plugins {
    id 'org.owasp.dependencycheck' version '7.0.0'
}

// Запусти: ./gradlew dependencyCheckAnalyze
```

## CSRF Protection

### Используй Spring Security CSRF protection
По умолчанию включена в Spring Boot.

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http
            .csrf()
            .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
            .and()
            // остальная конфигурация
    }
}
```

## Error Handling

### Не возвращай детали ошибок клиентам
Это информация для хакеров.

❌ Плохо:
```java
@ExceptionHandler(Exception.class)
public ResponseEntity<String> handleError(Exception e) {
    return ResponseEntity.status(500)
        .body("Error: " + e.getMessage());  // Может содержать чувствительные данные!
}
```

✅ Хорошо:
```java
@ExceptionHandler(Exception.class)
public ResponseEntity<ErrorResponse> handleError(Exception e) {
    log.error("Internal error occurred", e);  // Логируем полный стек
    return ResponseEntity.status(500)
        .body(new ErrorResponse("An error occurred. Please contact support."));
}
```

## Secrets Management

### Не коммитай секреты в код
Используй переменные окружения или vault решения.

❌ Плохо:
```java
public class Config {
    public static final String API_KEY = "sk-12345abcde";  // В коде!
    public static final String DB_PASSWORD = "super_secret";
}
```

✅ Хорошо:
```java
// application.yml
spring:
  datasource:
    password: ${DATABASE_PASSWORD}  # Из переменной окружения

// Java код
@Value("${DATABASE_PASSWORD}")
private String dbPassword;
```

## Regular Updates

### Следи за обновлениями Spring Boot
Обновляй регулярно для получения security patches.

```bash
# Проверь доступные обновления
./gradlew dependencyUpdates

# Обновись на новую версию
gradle-wrapper --gradle-version=8.0
```