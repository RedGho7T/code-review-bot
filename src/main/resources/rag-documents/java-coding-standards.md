# Java Coding Standards

## Именование

### Переменные - camelCase
Все переменные должны быть в camelCase с описательными именами.

❌ Плохо:
- String user_name = "John";
- String UserName = "John";
- String x = "value";

✅ Хорошо:
- String userName = "John";
- int maxRetries = 3;
- boolean isActive = true;

### Константы - UPPER_SNAKE_CASE
Статические финальные переменные пишутся в UPPER_SNAKE_CASE.

❌ Плохо:
- static final int maxRetries = 3;
- static final String defaultUser = "admin";

✅ Хорошо:
- static final int MAX_RETRIES = 3;
- static final String DEFAULT_USER = "admin";

### Классы - PascalCase
Имена классов пишутся с большой буквы, каждое слово с большой.

❌ Плохо:
- class user { }
- class process_request { }
- class getData { }

✅ Хорошо:
- class User { }
- class OrderProcessor { }
- class DataRetriever { }

### Методы - глаголы в camelCase
Имена методов начинаются с глагола в camelCase.

❌ Плохо:
- public void User() { }
- public void getData() { }
- public void process_data() { }

✅ Хорошо:
- public User createUser() { }
- public List<User> getAllUsers() { }
- public void processUserData() { }

## Null Safety

### Используй Optional вместо null
Никогда не возвращай null из методов. Используй Optional для опциональных значений.

❌ Плохо:
```java
public User getUser(String id) {
    if (id == null) {
        return null;
    }
    return repository.findById(id);
}

User user = getUser("123");
if (user != null) {
    String name = user.getName();
}
```

✅ Хорошо:
```java
public Optional<User> getUser(String id) {
    return repository.findById(id);
}

Optional<User> user = getUser("123");
String name = user
    .map(User::getName)
    .orElse("Unknown");
```

### Аннотация @Nullable
Если метод может вернуть null, явно отметь это аннотацией.

```java
import org.springframework.lang.Nullable;

@Nullable
public String getOptionalValue() {
    return maybeNull ? value : null;
}
```

## Методы

### Методы должны быть маленькие (max 30 строк)
Один метод = одна ответственность.

❌ Плохо:
```java
public void processOrder(Order order) {
    // 200 строк логики!
    // проверка, расчет, логирование, отправка письма, обновление БД...
}
```

✅ Хорошо:
```java
public void processOrder(Order order) {
    validateOrder(order);
    calculatePrice(order);
    sendConfirmation(order);
    updateDatabase(order);
}
```

### Методы должны иметь не более 3-4 параметров
Если параметров больше, используй объект.

❌ Плохо:
```java
public User createUser(String name, String email, String phone, 
                       String address, String city, String country) {
    // 6 параметров - слишком много!
}
```

✅ Хорошо:
```java
public User createUser(UserCreateRequest request) {
    // Все параметры в одном объекте
}
```

## Классы

### SRP - Single Responsibility Principle
Один класс = одна ответственность.

❌ Плохо:
```java
class User {
    String name;
    String email;
    
    void log(String msg) { }  // Логирование?
    void saveToDatabase() { }  // Работа с БД?
    void sendEmail() { }       // Email?
}
```

✅ Хорошо:
```java
class User {
    String name;
    String email;
}

class UserRepository {
    void save(User user) { }
}

class EmailService {
    void send(User user) { }
}
```

## Обработка исключений

### Ловушку специфичные исключения, не Exception
Избегай ловушки общего исключения Exception.

❌ Плохо:
```java
try {
    user = repository.findById(id);
} catch (Exception e) {
    log.error("Error", e);
}
```

✅ Хорошо:
```java
try {
    user = repository.findById(id);
} catch (UserNotFoundException e) {
    log.warn("User not found: {}", id);
} catch (DatabaseException e) {
    log.error("Database error", e);
    throw new RuntimeException(e);
}
```

### Логируй причину исключения
Всегда записывай стек вызовов для отладки.

```java
try {
    process();
} catch (IOException e) {
    // Хорошо: видим стек вызовов при debug логировании
    log.error("Failed to process file", e);
}
```

## Comments

### Комментарии для "Почему", не для "Что"
Код должен быть самодокументирующимся.

❌ Плохо:
```java
// Увеличить счетчик
count++;

// Проверить что пользователь существует
if (user != null) { }
```

✅ Хорошо:
```java
attemptCount++;

// Кешируем результат на 1 час чтобы reduce load на БД в peak часы
@Cacheable(value = "users", cacheManager = "onehourCache")
public User getUser(String id) { }
```

## Imports

### Используй конкретные импорты, не wildcard
Избегай `import com.example.*;`

❌ Плохо:
```java
import java.util.*;
import com.example.service.*;
```

✅ Хорошо:
```java
import java.util.List;
import java.util.Optional;
import com.example.service.UserService;
```

## Code Style

### Отступы - 4 пробела (не табы!)
Используй 4 пробела для отступа.

```java
public class Example {
    public void method() {              // 4 пробела
        if (condition) {                // 8 пробелов
            doSomething();              // 12 пробелов
        }
    }
}
```

### Максимум 100 символов на линию
Длинные строки хуже читать.

❌ Плохо:
```java
User user = userRepository.findUserWithComplexConditionAndLongNameByIdAndThenApplySomeTransformationLogic(id);
```

✅ Хорошо:
```java
User user = userRepository.findUserWithComplexCondition(id)
    .applyTransformation();
```

## Логирование

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

### Используй правильные уровни логирования
- ERROR: критические ошибки
- WARN: предупреждения
- INFO: важная информация
- DEBUG: информация для отладки

```java
log.error("Failed to process order", exception);  // Ошибка
log.warn("Retry attempt {} of {}", attempt, MAX_RETRIES);  // Предупреждение
log.info("Order processed successfully: {}", orderId);  // Инфо
log.debug("Processing details: {}", details);  // Отладка
```