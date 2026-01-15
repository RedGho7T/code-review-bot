# Testing Standards

## Unit Tests

### Тестируй одно - напиши один тест
Один @Test метод = один сценарий проверки.

❌ Плохо:
```java
@Test
public void testUser() {
    User user = new User("John", "john@example.com");
    assertThat(user.getName()).isEqualTo("John");
    assertThat(user.getEmail()).isEqualTo("john@example.com");
    user.setName("Jane");
    assertThat(user.getName()).isEqualTo("Jane");
}
```

✅ Хорошо:
```java
@Test
public void testUserCreation() {
    User user = new User("John", "john@example.com");
    assertThat(user.getName()).isEqualTo("John");
}

@Test
public void testUserNameUpdate() {
    User user = new User("John", "john@example.com");
    user.setName("Jane");
    assertThat(user.getName()).isEqualTo("Jane");
}
```

### Используй Arrange-Act-Assert паттерн
Структурируй тест на 3 части.

```java
@Test
public void testGetUser() {
    // Arrange: подготовка
    Long userId = 1L;
    User expectedUser = new User("John", "john@example.com");
    given(userRepository.findById(userId)).willReturn(Optional.of(expectedUser));
    
    // Act: действие
    User actualUser = userService.getUser(userId);
    
    // Assert: проверка
    assertThat(actualUser).isEqualTo(expectedUser);
}
```

### Используй осмысленные имена тестов
Имя теста = описание что он тестирует.

❌ Плохо:
```java
@Test
public void test1() { }

@Test
public void testMethod() { }
```

✅ Хорошо:
```java
@Test
public void shouldThrowExceptionWhenUserNotFound() { }

@Test
public void shouldReturnUserEmailWhenUserExists() { }
```

## Test Data

### Используй TestDataBuilder паттерн
Для создания сложных объектов в тестах.

```java
class UserTestDataBuilder {
    private String name = "John";
    private String email = "john@example.com";
    private boolean active = true;
    
    public UserTestDataBuilder withName(String name) {
        this.name = name;
        return this;
    }
    
    public User build() {
        return new User(name, email, active);
    }
}

// Использование:
@Test
public void test() {
    User user = new UserTestDataBuilder()
        .withName("Jane")
        .build();
}
```

### Не используй реальные данные в тестах
Используй фикстуры и mocks.

❌ Плохо:
```java
@Test
public void test() {
    userService.save(new User("real@gmail.com", "realPassword123"));
}
```

✅ Хорошо:
```java
@Test
public void test() {
    User testUser = TestDataBuilder.aUser().build();
    userService.save(testUser);
}
```

## Mocking

### Используй Mockito для unit тестов
Мокируй зависимости.

```java
@ExtendWith(MockitoExtension.class)
public class UserServiceTest {
    @Mock
    private UserRepository userRepository;
    
    @InjectMocks
    private UserService userService;
    
    @Test
    public void shouldGetUserWhenExists() {
        User user = new User("John");
        given(userRepository.findById(1L)).willReturn(Optional.of(user));
        
        User result = userService.getUser(1L);
        
        assertThat(result).isEqualTo(user);
    }
}
```

### Verify что методы были вызваны
Проверяй правильность взаимодействия между компонентами.

```java
@Test
public void shouldSaveUserWhenCreating() {
    User user = new User("John");
    
    userService.create(user);
    
    verify(userRepository).save(user);
    verify(emailService).sendWelcome(user);
}
```

### Используй BDD Mockito стиль (given-when-then)
Более читаемый способ написания тестов.

```java
@Test
public void shouldSendEmailWhenUserCreated() {
    // Given: предусловия
    User user = new User("John");
    given(emailService.isAvailable()).willReturn(true);
    
    // When: выполнение
    userService.create(user);
    
    // Then: проверка результата
    verify(emailService).send(user);
}
```

## Integration Tests

### Используй @SpringBootTest для integration тестов
Загружает полный контекст приложения.

```java
@SpringBootTest
public class UserControllerIntegrationTest {
    @Autowired
    private TestRestTemplate restTemplate;
    
    @Autowired
    private UserRepository userRepository;
    
    @Test
    public void shouldCreateUserViaAPI() {
        UserCreateRequest request = new UserCreateRequest("John", "john@example.com");
        
        ResponseEntity<User> response = restTemplate.postForEntity(
            "/api/users", request, User.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(userRepository.count()).isEqualTo(1);
    }
}
```

### Используй @DataJpaTest для тестирования репозиториев
Загружает только JPA контекст.

```java
@DataJpaTest
public class UserRepositoryTest {
    @Autowired
    private UserRepository repository;
    
    @Test
    public void shouldFindUserByEmail() {
        User user = new User("John", "john@example.com");
        repository.save(user);
        
        Optional<User> found = repository.findByEmail("john@example.com");
        
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("John");
    }
}
```

## Test Coverage

### Целевое покрытие: 80% линий кода
Не погоняйся за 100%, покрывай важные пути.

```java
// Этот путь важен и должен быть покрыт
public User getUser(Long id) {
    return repository.findById(id)
        .orElseThrow(UserNotFoundException::new);
}
```

### Тестируй edge cases
Граничные случаи, null, пустые значения.

```java
@Test
public void shouldReturnEmptyListWhenNoUsers() {
    given(repository.findAll()).willReturn(List.of());
    
    List<User> users = service.getAllUsers();
    
    assertThat(users).isEmpty();
}

@Test
public void shouldThrowExceptionWhenIdIsNull() {
    assertThatThrownBy(() -> service.getUser(null))
        .isInstanceOf(IllegalArgumentException.class);
}

@Test
public void shouldHandleEmptyString() {
    assertThatThrownBy(() -> service.getUser(""))
        .isInstanceOf(IllegalArgumentException.class);
}
```

## Performance Tests

### Тестируй производительность критичных операций
Используй assertThat для проверки времени выполнения.

```java
@Test
public void shouldReturnUsersWithin100Milliseconds() {
    // Подготовка: добавляем 1000 пользователей
    List<User> users = generateUsers(1000);
    repository.saveAll(users);
    
    // Выполнение и измерение
    long startTime = System.currentTimeMillis();
    List<User> result = repository.findAll();
    long duration = System.currentTimeMillis() - startTime;
    
    // Проверка
    assertThat(duration).isLessThan(100);
}
```

## Test Organization

### Структурируй папки тестов как исходный код
```
src/
  main/java/com/example/service/UserService.java
  test/java/com/example/service/UserServiceTest.java

src/
  main/java/com/example/repository/UserRepository.java
  test/java/com/example/repository/UserRepositoryTest.java
```

### Используй вложенные классы @Nested для группировки
```java
@DisplayName("UserService Tests")
class UserServiceTest {
    @Nested
    @DisplayName("when finding user")
    class FindUserTests {
        @Test
        void shouldReturnUserWhenFound() { }
    }
    
    @Nested
    @DisplayName("when creating user")
    class CreateUserTests {
        @Test
        void shouldThrowExceptionWhenNameIsNull() { }
    }
}
```