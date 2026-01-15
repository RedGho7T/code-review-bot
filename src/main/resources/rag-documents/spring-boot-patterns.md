# Spring Boot Patterns

## Dependency Injection

### Используй Constructor Injection
Никогда не используй field injection с @Autowired.

❌ Плохо (field injection):
```java
@RestController
public class UserController {
    @Autowired
    private UserService userService;  // Сложно тестировать!
}
```

✅ Хорошо (constructor injection):
```java
@RestController
public class UserController {
    private final UserService userService;
    
    public UserController(UserService userService) {
        this.userService = userService;
    }
}
```

Преимущества:
- Явные зависимости (видны в конструкторе)
- Легко тестировать (передаем mock)
- Immutable поля (final)
- Работает с Lombok @RequiredArgsConstructor

### Используй Lombok @RequiredArgsConstructor
Автоматически создает конструктор для final полей.

```java
@RestController
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;
    private final UserRepository userRepository;
}
```

## REST Endpoints

### Используй правильные HTTP методы
GET, POST, PUT, PATCH, DELETE имеют разные семантики.

✅ Правильно:
```java
// GET - получить данные (no side effects)
@GetMapping("/users/{id}")
public User getUser(@PathVariable Long id) { }

// POST - создать новый ресурс
@PostMapping("/users")
public User createUser(@RequestBody User user) { }

// PUT - обновить весь ресурс
@PutMapping("/users/{id}")
public User updateUser(@PathVariable Long id, @RequestBody User user) { }

// PATCH - обновить часть ресурса
@PatchMapping("/users/{id}")
public User partialUpdate(@PathVariable Long id, @RequestBody Map<String, Object> updates) { }

// DELETE - удалить ресурс
@DeleteMapping("/users/{id}")
public void deleteUser(@PathVariable Long id) { }
```

### Возвращай правильные HTTP статусы
200 OK, 201 Created, 204 No Content, 400 Bad Request, 404 Not Found и т.д.

✅ Правильно:
```java
@PostMapping("/users")
public ResponseEntity<User> createUser(@RequestBody User user) {
    User created = userService.save(user);
    return ResponseEntity.created(
        URI.create("/users/" + created.getId())
    ).body(created);
}

@GetMapping("/users/{id}")
public ResponseEntity<User> getUser(@PathVariable Long id) {
    return userService.findById(id)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
}

@DeleteMapping("/users/{id}")
public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
    userService.delete(id);
    return ResponseEntity.noContent().build();
}
```

## Service Layer

### Структурируй: Controller → Service → Repository
Трехслойная архитектура.

```java
// Layer 1: Controller (HTTP endpoints)
@RestController
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;
    
    @GetMapping("/{id}")
    public User getUser(@PathVariable Long id) {
        return userService.getUserById(id);
    }
}

// Layer 2: Service (бизнес-логика)
@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    
    public User getUserById(Long id) {
        return userRepository.findById(id)
            .orElseThrow(UserNotFoundException::new);
    }
}

// Layer 3: Repository (работа с БД)
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
}
```

## Configuration

### Используй @Configuration для сложных бинов
Когда нужна логика при создании бина.

```java
@Configuration
public class AppConfig {
    @Bean
    public RestTemplate restTemplate() {
        RestTemplate template = new RestTemplate();
        template.setInterceptors(...);
        return template;
    }
}
```

### Используй @ConfigurationProperties для типизированных конфигов
Вместо @Value("${...}") для каждого параметра.

```java
@Configuration
@ConfigurationProperties(prefix = "app.features")
@Data
public class FeatureProperties {
    private boolean cacheEnabled;
    private int cacheTimeoutSeconds;
    private List<String> enabledModules;
}

// Использование:
@Service
public class MyService {
    private final FeatureProperties features;
    
    public void process() {
        if (features.isCacheEnabled()) {
            cache.put(...);
        }
    }
}
```

## Transactional

### Используй @Transactional для операций с БД
Гарантирует ACID свойства.

```java
@Service
public class UserService {
    // Если выкинется исключение, всё откатится (rollback)
    @Transactional
    public void transferMoney(User from, User to, BigDecimal amount) {
        from.decreaseBalance(amount);
        to.increaseBalance(amount);
    }
    
    // Read-only операции - оптимизация
    @Transactional(readOnly = true)
    public List<User> getAllUsers() {
        return repository.findAll();
    }
}
```

## Logging

### Используй SLF4J с Lombok
Не создавай логгер вручную.

❌ Плохо:
```java
import java.util.logging.Logger;
public class UserService {
    private static final Logger logger = Logger.getLogger(UserService.class.getName());
    logger.info("Processing...");
}
```

✅ Хорошо:
```java
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class UserService {
    public void process() {
        log.info("Processing...");
    }
}
```

### Используй параметризованные логи
Избегай конкатенации строк.

❌ Плохо:
```java
log.info("User " + userName + " logged in at " + timestamp);
```

✅ Хорошо:
```java
log.info("User {} logged in at {}", userName, timestamp);
```

## Exception Handling

### Создавай кастомные исключения
Не используй базовые Exception и RuntimeException.

```java
public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(String userId) {
        super("User not found: " + userId);
    }
}

// Использование:
@Service
public class UserService {
    public User getUser(String id) {
        return userRepository.findById(id)
            .orElseThrow(() -> new UserNotFoundException(id));
    }
}
```

### Используй @RestControllerAdvice для централизованной обработки
Обрабатывай исключения в одном месте.

```java
@RestControllerAdvice
public class ExceptionHandler {
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException e) {
        return ResponseEntity.status(404)
            .body(new ErrorResponse("User not found", e.getMessage()));
    }
}
```

## Testing

### Структурируй тесты: Arrange, Act, Assert
```java
@Test
public void testGetUser() {
    // Arrange: подготовка данных
    User expectedUser = new User("John", "john@example.com");
    given(userRepository.findById(1L)).willReturn(Optional.of(expectedUser));
    
    // Act: выполнение
    User actualUser = userService.getUser(1L);
    
    // Assert: проверка результата
    assertThat(actualUser).isEqualTo(expectedUser);
}
```

### Используй @SpringBootTest только для integration тестов
Для unit тестов используй Mockito.

```java
// Unit test (быстро)
@ExtendWith(MockitoExtension.class)
class UserServiceTest {
    @Mock private UserRepository repository;
    @InjectMocks private UserService service;
}

// Integration test (медленнее, но полнее)
@SpringBootTest
class UserIntegrationTest {
    @Autowired private UserService service;
}
```