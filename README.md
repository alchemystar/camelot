# Spring Call Path Analyzer (Static)

一个面向 Spring 工程的静态分析工具，用于从接口入口推导“可能调用路径”（保守分析，宁可多报）。

## 已实现能力

- Java 源码解析（`javaparser-core`）
- Spring 注解入口识别
  - `@RestController` / `@Controller`
  - `@RequestMapping` / `@GetMapping` / `@PostMapping` / `@PutMapping` / `@DeleteMapping` / `@PatchMapping`
- Bean 与注入关系识别（注解）
  - `@Component` / `@Service` / `@Repository` / `@Configuration`
  - `@Autowired` / `@Inject` / `@Resource`
  - 构造器注入（`this.field = param`）
- XML 解析（`*.xml`）
  - `<bean id class>`
  - `<alias>`
  - `<property name ref>` / 嵌套 `<ref bean="...">`
  - `<constructor-arg ref>`（已记录，当前不参与字段名调用匹配）
  - `SimpleUrlHandlerMapping` 的 `urlMap`（`<entry key value-ref>` 和 `<prop key>`）
- 调用图构建与路径搜索
  - 方法级调用边
  - 从注解入口和 XML URL 入口做 DFS
  - 可配置 `max-depth` / `max-paths`
- 输出
  - `analysis-report.json`
  - `call-graph.dot`

## 运行

JDK 要求：`Java 8+`（当前项目以 `source/target 1.8` 编译）。

```bash
mvn -q compile
mvn -q exec:java -Dexec.args="--project /path/to/your-spring-project --out /path/to/output --max-depth 8 --max-paths 200"
```

参数说明：

- `--project`：被分析工程根目录，默认当前目录
- `--out`：输出目录，默认 `build/reports/spring-call-path`
- `--max-depth`：路径最大深度，默认 `8`
- `--max-paths`：每个入口最多输出路径数，默认 `200`

## 示例

仓库内提供了示例工程：`examples/demo-spring-app`。

运行：

```bash
mvn -q exec:java -Dexec.args="--project ./examples/demo-spring-app --out ./build/reports/demo"
```

预期可看到两类入口：

- 注解入口：`/users/{id}`
- XML 入口：`/legacy/users/{id}`

## 已知限制（当前版本）

- 未处理反射、动态代理细节、SpEL、运行时条件装配
- 对局部变量类型、重载解析、跨模块依赖解析是“启发式”而非完整类型系统
- `@Bean` 方法产出的 Bean 与复杂 AOP/事务织入尚未完整建模
