# Compact 命令设计：精简多版本合并后的 exec 文件

日期：2026-08-03

## 1. 背景与问题

自定义扩展的 JaCoCo 支持多版本覆盖率合并（`Report` 命令的 `--mergeExecfilepath` / `--mergeClassfilepath`）。
合并后的 exec 文件会持续膨胀（实测可达 600M），原因：

- exec 中的每条执行数据块以 `classId` 为标识（`ExecutionDataStore` 是 `HashMap<Long, ExecutionData>`）。
- `classId = CRC64.classId(class 字节码)`（`org.jacoco.core.internal.data.CRC64`）。
- 同一类在不同版本中字节码不同 → classId 不同 → 合并时新旧版本的条目**同时保留**，且旧版本的条目永远不会被清除，只增不减。

## 2. 目标与非目标

### 目标

新增 CLI 命令 `compact`：输入合并后的 exec + 目标（最新）版本的 classfiles，输出精简后的 exec。
过滤规则：**只保留 classId 属于目标版本 classfiles 集合的执行数据条目**；会话信息全部保留。
流式处理，不把 600M exec 整体加载进内存。

### 非目标

- 不使用 sourcefiles（classId 由 class 字节码计算，sourcefiles 与过滤无关）。
- 不按类名/包名做白名单黑名单过滤（YAGNI）。
- 不修改 `merge` / `report` 命令的现有语义。
- 不修改 exec 文件格式、不新增 block 类型。

## 3. 命令接口

```
java -jar jacococli.jar compact --classfiles <path> [--classfiles <path> ...] <execfiles...> --destfile <path>
```

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `--classfiles <path>` | 可重复 `List<File>` | 是 | 目标版本 class 文件/目录；目录递归扫描，jar 文件解压扫描 |
| `--destfile <path>` | `File` | 是 | 精简后输出 exec 文件 |
| `<execfiles...>` | 位置参数 `List<File>` | 否 | 待精简的 exec 文件（通常为合并后的大文件），可多个 |

命令描述：`"Compacts exec files by removing execution data for classes not matching the given class files."`

## 4. 执行流程

新文件 `org.jacoco.cli/src/org/jacoco/cli/internal/commands/Compact.java`，仿 `Merge` 命令结构。

### 4.1 第一阶段：构建目标 classId 集合

遍历每个 `--classfiles` 路径：

- 目录 → 递归扫描
- 文件 → 读取字节，用 `ContentTypeDetector`（`org.jacoco.core.internal.ContentTypeDetector`）判断类型：
  - `CLASSFILE` → `CRC64.classId(bytes)` 加入 `Set<Long>`
  - `ZIPFILE` → 遍历 zip 内后缀为 `.class` 的条目，同上
  - 其他类型（GZ/PACK200/未知）→ 跳过

完成后打印：`[INFO] Found N classes in target class files.`

### 4.2 第二阶段：流式过滤

```
ExecutionDataWriter writer = new ExecutionDataWriter(new BufferedOutputStream(new FileOutputStream(destfile)));
对每个输入 exec：
    ExecutionDataReader reader = new ExecutionDataReader(new BufferedInputStream(new FileInputStream(file)));
    reader.setSessionInfoVisitor(writer);                    // 会话信息全部透传
    reader.setExecutionDataVisitor(过滤 visitor);            // 见下
    reader.read();
```

过滤 visitor 逻辑：

```java
void visitClassExecution(ExecutionData data) {
    total++;
    if (targetIds.contains(data.getId())) {   // Long 装箱注意用 Set<Long>
        kept++;
        writer.visitClassExecution(data);
    }
}
```

注意：

- `ExecutionDataWriter.visitClassExecution` 对无命中的条目（`hasHits() == false`）不写出——与 `merge` 行为一致，无需特殊处理。
- `CompactDataOutput.writeBooleanArray` 使用变长编码，探针数组按原样写出，大小无额外膨胀。
- 过滤只发生在执行数据块；`read()` 对未知 block 类型抛 `IOException` 的行为保持不变（正常 dump 产物只含 header/session/exec 三类 block，`BLOCK_DOWNBBZX` 等自定义 block 只出现在 agent 实时连接流中，不会出现在磁盘 exec 文件里）。

### 4.3 第三阶段：统计输出

- `[INFO] Compacted: kept X of Y classes (dropped Z).`
- 若 `kept == 0`：`[WARN] No execution data matches the given class files. The class files may be from an old version.`（防误传旧版本 class 目录）

## 5. 边界情况

| 场景 | 行为 |
|---|---|
| 无输入 exec | `[WARN] No execution data files provided.`（与 `merge` 一致），仍写出仅含 header + 会话信息的空输出 |
| 缺少 `--destfile` 或 `--classfiles` | args4j 报错并打印 usage（`required = true`） |
| destfile 与任一输入 exec 路径相同 | 报错拒绝（流式写入会截断正在读取的输入文件） |
| 多个输入 exec 含重复 classId | 重复条目原样写出；下游加载时自动合并，无正确性问题 |
| 类名相同、classId 不同（历史版本） | 按 id 过滤自动丢弃旧版本，无需额外逻辑 |
| classfiles 为空目录 | 目标集合为空 → 所有条目被丢弃 → 触发 kept=0 的 WARN |
| classfiles 路径不存在 | 读文件时抛 `FileNotFoundException`（IOException），与 `Analyzer.analyzeAll` 行为一致，由命令直接抛出 |

## 6. 代码变更清单

1. **新增** `org.jacoco.cli/src/org/jacoco/cli/internal/commands/Compact.java`（~120 行，含注释风格与现有自定义代码一致的中文注释）
2. **修改** `org.jacoco.cli/src/org/jacoco/cli/internal/commands/AllCommands.java` — 注册 `new Compact()`
3. **新增** `org.jacoco.cli.test/src/org/jacoco/cli/internal/commands/CompactTest.java`（仿 `MergeTest`，继承 `CommandTestBase`）

不修改 core 模块（CRC64 / ContentTypeDetector / ExecutionDataReader / Writer 均为现有 public API）。

## 7. 测试计划（`CompactTest`）

测试基建：`CommandTestBase.execute(...)` 运行命令；用 `ExecutionDataWriter` 手工构造 exec 文件；`ExecFileLoader` 读取结果断言。

| 用例 | 断言 |
|---|---|
| 无参数 | 失败，提示 `--destfile`、`--classfiles` 必填 |
| 无输入 exec | 成功 + WARN，输出为空 exec |
| 正常精简 | 输入含命中类 A（id 匹配）、不命中类 B、会话信息；输出只含 A 且会话保留 |
| 无命中场景 | 成功 + WARN，输出不含任何执行数据 |
| 目录递归扫描 classfiles | 子目录中的 .class 也计入目标集合 |
| jar 作为 classfiles 输入 | jar 内 .class 计入目标集合 |
| destfile 与输入同路径 | 失败并报错 |

## 8. 验证方式

```bash
./mvnw -pl org.jacoco.cli,org.jacoco.cli.test test   # 或等价命令运行 CompactTest
```

构建产物 `org.jacoco.cli/target/jacococli.jar` 手工验证：

```bash
java -jar jacococli.jar compact \
  --classfiles <目标版本 class 目录> \
  <合并后的 exec> \
  --destfile <精简后的 exec>
```

预期：输出文件体积大幅缩小（仅剩目标版本条目），且后续用 `report` 生成覆盖率报告的结果与精简前一致。
