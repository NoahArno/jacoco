# compact 命令实现计划（精简多版本合并 exec）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 JaCoCo CLI `compact` 命令：输入合并后的 exec 文件 + 目标版本 classfiles，流式过滤掉不属于目标版本 classId 的执行数据，输出精简后的 exec。

**Architecture:** 新命令仿 `Merge` 命令结构，全部逻辑在 `Compact.java` 内。第一阶段遍历 classfiles（目录递归 + zip 内 `.class` 条目）用 `CRC64.classId(byte[])` 构建目标 classId 集合；第二阶段用 `ExecutionDataReader` + 自定义 `IExecutionDataVisitor` 逐块读取输入 exec，classId 命中的执行数据转发给 `ExecutionDataWriter` 边读边写，会话信息全部透传。不修改 core 模块与 exec 文件格式。

**Tech Stack:** Java（目标 1.8+，代码风格沿用现有 jacoco fork：tab 缩进、中文注释、EPL 文件头）、Maven（mvnw）、JUnit 4 + args4j。

## Global Constraints

（来自 `docs/superpowers/specs/2026-08-03-compact-exec-design.md`，全部逐条落实）

- 过滤规则：只保留 `classId ∈ 目标版本 classfiles 集合` 的执行数据条目；会话信息全部透传。
- 命令签名：`compact --classfiles <path> [--classfiles <path> ...] <execfiles...> --destfile <path>`，`--classfiles` 与 `--destfile` 均 `required = true`。
- 流式处理：不得把输入 exec 整体加载进内存（不用 `ExecFileLoader` 的 store）。
- 无输入 exec：打印 `[WARN] No execution data files provided.`，仍写出仅含 header + 会话信息的空输出。
- 统计：`[INFO] Compacted: kept %d of %d classes (dropped %d).`
- 警告：仅当 `total > 0 && kept == 0` 打印 `[WARN] No execution data matches the given class files. The class files may be from an old version.`
- destfile 与任一输入 exec 规范路径相同 → `err` 打印 `[ERROR] The destfile must not be one of the input exec files: <destfile>` 并返回 `-1`。
- classfiles 路径不存在 → 直接抛 `FileNotFoundException`（IOException），不做静默处理。
- 命令描述：`"Compacts exec files by removing execution data for classes not matching the given class files."`
- `AllCommands.get()` 中 `new Compact()` **追加在列表末尾**（`new Version(), new Compact()`），因为 `MainTest` 断言子串 `"dump|instrument|merge|report"`，插中间会破坏该子串。
- 新文件必须带 EPL 版权头（与仓库所有文件一致）。
- 测试运行命令：`./mvnw -pl org.jacoco.agent.rt,org.jacoco.cli.test -am package -Dtest=CompactTest -DfailIfNoTests=false`（本机 Maven 3.9.11 + JDK 25 下 `test` 阶段会因 agent runtime jar 未打包导致 surefire VM 崩溃，必须用 `package` 阶段 + 显式包含 `org.jacoco.agent.rt`）。
- args4j 2.0.28 行为：多个必填参数缺失时只报告第一个（`Option "--classfiles" is required`，带引号）；help/usage 文本中的选项名不带引号。因此测试断言对第二个必填参数用不带引号的文本。
- 测试中目标 class 文件引用必须用 `org/jacoco/cli/internal/CommandTestBase.class`（不可用 CLI 模块的 `Command.class`）：`CommandTestBase.getClassPath()` 返回测试模块的 `target/classes`，CLI 模块在 reactor 构建中是 jar 依赖，其类文件不在该目录下，引用会抛 FileNotFoundException（发生在 execute() 之前）。

---

### Task 1: compact 命令骨架与注册

**Files:**
- Create: `org.jacoco.cli/src/org/jacoco/cli/internal/commands/Compact.java`
- Modify: `org.jacoco.cli/src/org/jacoco/cli/internal/commands/AllCommands.java:31-34`
- Test: `org.jacoco.cli.test/src/org/jacoco/cli/internal/commands/CompactTest.java`

**Interfaces:**
- Consumes: `Command`（`org.jacoco.cli.internal.Command`，含 `name()` = 类名小写 → "compact"、抽象 `description()`、抽象 `execute(PrintWriter, PrintWriter)`）、args4j `@Argument`/`@Option`、`CommandTestBase`（`execute(String...)`/`assertOk()`/`assertFailure()`/`assertContains(String, StringWriter)`）。
- Produces: `Compact` 命令类（Task 2/3 在其 `execute` 内填充逻辑）；`CompactTest` 测试类（Task 2/3 追加测试方法）。

- [ ] **Step 1: 写失败测试（usage 错误场景）**

创建 `org.jacoco.cli.test/src/org/jacoco/cli/internal/commands/CompactTest.java`（仿 `MergeTest`）：

```java
/*******************************************************************************
 * Copyright (c) 2009, 2025 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    Marc R. Hoffmann - initial API and implementation
 *
 *******************************************************************************/
package org.jacoco.cli.internal.commands;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.jacoco.cli.internal.CommandTestBase;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfo;
import org.jacoco.core.internal.InputStreams;
import org.jacoco.core.internal.data.CRC64;
import org.jacoco.core.tools.ExecFileLoader;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for {@link Compact}.
 */
public class CompactTest extends CommandTestBase {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void should_print_usage_when_no_options_are_given()
			throws Exception {
		execute("compact");

		assertFailure();
		assertContains("\"--classfiles\"", err);
		assertContains("--destfile", err);
		assertContains("java -jar jacococli.jar compact [<execfiles> ...]",
				err);
	}

}
```

- [ ] **Step 2: 运行测试验证失败**

运行: `./mvnw -pl org.jacoco.agent.rt,org.jacoco.cli.test -am package -Dtest=CompactTest -DfailIfNoTests=false`
预期: BUILD FAILURE，测试失败（CompactTest 对 `Compact` 的引用仅存在于 javadoc `{@link}` 注释中，可正常编译；失败原因是命令未注册时 args4j 报 `"compact" is not a valid value for "<command>"`，断言不匹配）。

- [ ] **Step 3: 实现命令骨架**

创建 `org.jacoco.cli/src/org/jacoco/cli/internal/commands/Compact.java`：

```java
/*******************************************************************************
 * Copyright (c) 2009, 2025 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    Marc R. Hoffmann - initial API and implementation
 *
 *******************************************************************************/
package org.jacoco.cli.internal.commands;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import org.jacoco.cli.internal.Command;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

/**
 * The <code>compact</code> command.
 */
public class Compact extends Command {

	@Argument(usage = "list of JaCoCo *.exec files to read", metaVar = "<execfiles>")
	List<File> execfiles = new ArrayList<File>();

	// 目标版本的 class 文件，用于计算需要保留的 classId 集合
	@Option(name = "--classfiles", usage = "location of the target version Java class files", metaVar = "<path>", required = true)
	List<File> classfiles = new ArrayList<File>();

	@Option(name = "--destfile", usage = "file to write compacted execution data to", metaVar = "<path>", required = true)
	File destfile;

	@Override
	public String description() {
		return "Compacts exec files by removing execution data for classes not matching the given class files.";
	}

	@Override
	public int execute(final PrintWriter out, final PrintWriter err)
			throws IOException {
		return 0;
	}

}
```

修改 `org.jacoco.cli/src/org/jacoco/cli/internal/commands/AllCommands.java`，把第 32-33 行：

```java
		return Arrays.asList(new Dump(), new Instrument(), new Merge(),
				new Report(), new ClassInfo(), new ExecInfo(), new Version());
```

改为（`new Compact()` 必须追加在末尾，否则 MainTest 的子串断言会失败）：

```java
		return Arrays.asList(new Dump(), new Instrument(), new Merge(),
				new Report(), new ClassInfo(), new ExecInfo(), new Version(),
				new Compact());
```

- [ ] **Step 4: 运行测试验证通过**

运行: `./mvnw -pl org.jacoco.agent.rt,org.jacoco.cli.test -am package -Dtest=CompactTest -DfailIfNoTests=false`
预期: BUILD SUCCESS，`Tests run: 1, Failures: 0`。

- [ ] **Step 5: 提交**

```bash
git add org.jacoco.cli/src/org/jacoco/cli/internal/commands/Compact.java org.jacoco.cli/src/org/jacoco/cli/internal/commands/AllCommands.java org.jacoco.cli.test/src/org/jacoco/cli/internal/commands/CompactTest.java
git commit -m "feat(cli): 新增 compact 命令骨架并注册"
```

---

### Task 2: 目标 classId 集合构建 + 流式过滤核心逻辑

**Files:**
- Modify: `org.jacoco.cli/src/org/jacoco/cli/internal/commands/Compact.java`（填充 `execute` 全部逻辑 + 私有方法 + 内部类）
- Test: `org.jacoco.cli.test/src/org/jacoco/cli/internal/commands/CompactTest.java`（追加 2 个测试方法 + 3 个私有辅助方法）

**Interfaces:**
- Consumes: `ExecutionDataWriter`（构造即写 header；`visitSessionInfo`/`visitClassExecution` 透传接口）、`ExecutionDataReader`（`setSessionInfoVisitor`/`setExecutionDataVisitor`/`read()`）、`IExecutionDataVisitor`（`org.jacoco.core.data`）、`ContentTypeDetector`（`org.jacoco.core.internal`，常量 `CLASSFILE`/`ZIPFILE`，`getType()`/`getInputStream()`）、`InputStreams.readFully(InputStream)`（`org.jacoco.core.internal`）、`CRC64.classId(byte[])`（`org.jacoco.core.internal.data`）。
- Produces: 私有方法 `collectClassIds(PrintWriter)` / `collectClassIds(File, Set<Long>)` / `collectStreamClassIds(InputStream, Set<Long>)` / `collectZipClassIds(InputStream, Set<Long>)`，内部类 `Counter`（Task 3 复用）。

- [ ] **Step 1: 写失败测试（基本过滤 + 会话保留）**

在 `CompactTest.java` 中、`should_print_usage_when_no_options_are_given` 方法后追加两个测试方法：

```java
	@Test
	public void should_keep_only_matching_classes() throws Exception {
		// 用测试类路径上的真实 class 文件计算目标 classId
		final File targetClass = new File(getClassPath(),
				"org/jacoco/cli/internal/CommandTestBase.class");
		final long targetId = CRC64.classId(
				InputStreams.readFully(new FileInputStream(targetClass)));
		// 输入 exec：一个匹配目标版本，一个不匹配（历史版本残留）
		final File input = new File(tmp.getRoot(), "input.exec");
		final FileOutputStream execout = new FileOutputStream(input);
		final ExecutionDataWriter writer = new ExecutionDataWriter(execout);
		writer.visitClassExecution(new ExecutionData(targetId,
				"org/jacoco/cli/internal/Command", new boolean[] { true }));
		writer.visitClassExecution(new ExecutionData(0x12345678L,
				"org/jacoco/cli/internal/Stale", new boolean[] { true }));
		execout.close();

		final File dest = new File(tmp.getRoot(), "output.exec");
		execute("compact", "--classfiles", getClassPath(),
				input.getAbsolutePath(), "--destfile",
				dest.getAbsolutePath());

		assertOk();
		assertContains("[INFO] Compacted: kept 1 of 2 classes (dropped 1).",
				out);
		assertEquals(Collections.singleton("org/jacoco/cli/internal/Command"),
				loadExecFile(dest));
	}

	@Test
	public void should_keep_session_info() throws Exception {
		final File input = new File(tmp.getRoot(), "session.exec");
		final FileOutputStream execout = new FileOutputStream(input);
		final ExecutionDataWriter writer = new ExecutionDataWriter(execout);
		writer.visitSessionInfo(new SessionInfo("s1", 1L, 2L));
		execout.close();

		final File dest = new File(tmp.getRoot(), "output.exec");
		execute("compact", "--classfiles", getClassPath(),
				input.getAbsolutePath(), "--destfile",
				dest.getAbsolutePath());

		assertOk();
		final ExecFileLoader loader = new ExecFileLoader();
		loader.load(dest);
		assertEquals(1, loader.getSessionInfoStore().getInfos().size());
		assertEquals("s1",
				loader.getSessionInfoStore().getInfos().get(0).getId());
	}
```

在类末尾（最后一个 `}` 之前）追加私有辅助方法：

```java
	private File createExecFile(String name, long id) throws IOException {
		final File file = new File(tmp.getRoot(), name + ".exec");
		final FileOutputStream execout = new FileOutputStream(file);
		final ExecutionDataWriter writer = new ExecutionDataWriter(execout);
		writer.visitClassExecution(
				new ExecutionData(id, name, new boolean[] { true }));
		execout.close();
		return file;
	}

	private Set<String> loadExecFile(File file) throws IOException {
		final ExecFileLoader loader = new ExecFileLoader();
		loader.load(file);
		final Set<String> names = new HashSet<String>();
		for (ExecutionData d : loader.getExecutionDataStore().getContents()) {
			names.add(d.getName());
		}
		return names;
	}
```

（Task 3 还会追加 `copy` 辅助方法；此时 `FileInputStream`/`InputStreams`/`CRC64`/`SessionInfo`/`ExecFileLoader` 的 import 已在 Task 1 的测试文件中声明，无需改动 import。）

- [ ] **Step 2: 运行测试验证失败**

运行: `./mvnw -pl org.jacoco.agent.rt,org.jacoco.cli.test -am package -Dtest=CompactTest -DfailIfNoTests=false`
预期: BUILD FAILURE 或测试失败（`execute` 目前返回 0 不写输出文件 → `loadExecFile(dest)` 抛 `FileNotFoundException`，且 `[INFO] Compacted:` 断言不匹配）。

- [ ] **Step 3: 实现过滤逻辑**

把 `Compact.java` 的 `execute` 方法替换为完整实现，并在类内追加私有方法、内部类。`execute` 完整代码：

```java
	@Override
	public int execute(final PrintWriter out, final PrintWriter err)
			throws IOException {
		final File parent = destfile.getParentFile();
		if (parent != null) {
			parent.mkdirs();
		}
		// 第一阶段：遍历目标版本 class 文件，构建需要保留的 classId 集合
		final Set<Long> targetIds = collectClassIds(out);
		int total = 0;
		int kept = 0;
		final BufferedOutputStream output = new BufferedOutputStream(
				new FileOutputStream(destfile));
		try {
			final ExecutionDataWriter writer = new ExecutionDataWriter(output);
			if (execfiles.isEmpty()) {
				out.println("[WARN] No execution data files provided.");
			} else {
				for (final File file : execfiles) {
					out.printf("[INFO] Loading execution data file %s.%n",
							file.getAbsolutePath());
					final BufferedInputStream input = new BufferedInputStream(
							new FileInputStream(file));
					try {
						final ExecutionDataReader reader = new ExecutionDataReader(
								input);
						reader.setSessionInfoVisitor(writer);
						final Counter counter = new Counter(writer,
								targetIds);
						reader.setExecutionDataVisitor(counter);
						reader.read();
						total += counter.total;
						kept += counter.kept;
					} finally {
						input.close();
					}
				}
			}
		} finally {
			output.close();
		}
		out.printf("[INFO] Compacted: kept %d of %d classes (dropped %d).%n",
				Integer.valueOf(kept), Integer.valueOf(total),
				Integer.valueOf(total - kept));
		if (total > 0 && kept == 0) {
			out.println(
					"[WARN] No execution data matches the given class files. The class files may be from an old version.");
		}
		return 0;
	}
```

追加的私有方法与内部类（放在 `execute` 之后）：

```java
	/**
	 * 遍历目标版本 class 文件（目录递归、zip 内条目递归），计算全部 classId
	 *
	 * @param out
	 *            输出流，打印扫描到的类数量
	 * @return 目标版本 classId 集合
	 * @throws IOException
	 *             文件读取失败时抛出
	 */
	private Set<Long> collectClassIds(final PrintWriter out)
			throws IOException {
		final Set<Long> ids = new HashSet<Long>();
		int count = 0;
		for (final File file : classfiles) {
			count += collectClassIds(file, ids);
		}
		out.printf("[INFO] Found %d classes in target class files.%n",
				Integer.valueOf(count));
		return ids;
	}

	private int collectClassIds(final File file, final Set<Long> ids)
			throws IOException {
		if (file.isDirectory()) {
			int count = 0;
			final File[] children = file.listFiles();
			if (children != null) {
				for (final File child : children) {
					count += collectClassIds(child, ids);
				}
			}
			return count;
		}
		final FileInputStream in = new FileInputStream(file);
		try {
			return collectStreamClassIds(in, ids);
		} finally {
			in.close();
		}
	}

	/**
	 * 识别流内容类型：class 文件直接计算 classId，zip 则遍历内部条目，
	 * 其余类型（gzip/pack200/未知）跳过
	 */
	private int collectStreamClassIds(final InputStream input,
			final Set<Long> ids) throws IOException {
		final ContentTypeDetector detector = new ContentTypeDetector(input);
		switch (detector.getType()) {
		case ContentTypeDetector.CLASSFILE:
			ids.add(Long.valueOf(CRC64.classId(InputStreams
					.readFully(detector.getInputStream()))));
			return 1;
		case ContentTypeDetector.ZIPFILE:
			return collectZipClassIds(detector.getInputStream(), ids);
		default:
			return 0;
		}
	}

	/**
	 * 遍历 zip 内后缀为 .class 的条目
	 */
	private int collectZipClassIds(final InputStream input,
			final Set<Long> ids) throws IOException {
		final ZipInputStream zip = new ZipInputStream(input);
		ZipEntry entry;
		int count = 0;
		while ((entry = zip.getNextEntry()) != null) {
			if (entry.getName().endsWith(".class")) {
				count += collectStreamClassIds(zip, ids);
			}
		}
		return count;
	}

	/**
	 * 统计执行数据总条数与保留条数，并将目标版本的执行数据转发给 writer
	 */
	private static class Counter implements IExecutionDataVisitor {

		private final ExecutionDataWriter writer;

		private final Set<Long> targetIds;

		private int total = 0;

		private int kept = 0;

		Counter(final ExecutionDataWriter writer, final Set<Long> targetIds) {
			this.writer = writer;
			this.targetIds = targetIds;
		}

		public void visitClassExecution(final ExecutionData data) {
			total++;
			// 只保留 classId 命中目标版本的条目，历史版本的条目直接丢弃
			if (targetIds.contains(Long.valueOf(data.getId()))) {
				kept++;
				writer.visitClassExecution(data);
			}
		}
	}
```

`Compact.java` 的 import 区替换为：

```java
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.jacoco.cli.internal.Command;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.IExecutionDataVisitor;
import org.jacoco.core.internal.ContentTypeDetector;
import org.jacoco.core.internal.InputStreams;
import org.jacoco.core.internal.data.CRC64;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
```

- [ ] **Step 4: 运行测试验证通过**

运行: `./mvnw -pl org.jacoco.agent.rt,org.jacoco.cli.test -am package -Dtest=CompactTest -DfailIfNoTests=false`
预期: BUILD SUCCESS，`Tests run: 3, Failures: 0`。

- [ ] **Step 5: 提交**

```bash
git add org.jacoco.cli/src/org/jacoco/cli/internal/commands/Compact.java org.jacoco.cli.test/src/org/jacoco/cli/internal/commands/CompactTest.java
git commit -m "feat(cli): compact 命令实现目标 classId 集合构建与流式过滤"
```

---

### Task 3: 边界防护与输入形态测试

**Files:**
- Modify: `org.jacoco.cli/src/org/jacoco/cli/internal/commands/Compact.java`（`execute` 开头加 destfile 同路径防护）
- Test: `org.jacoco.cli.test/src/org/jacoco/cli/internal/commands/CompactTest.java`（追加 5 个测试方法 + `copy` 辅助方法 + 2 个 import）

**Interfaces:**
- Consumes: Task 2 产出的 `createExecFile(String, long)` / `loadExecFile(File)` 辅助方法、`collectClassIds`/`Counter`。
- Produces: 无新接口；验证全部规格边界行为。

- [ ] **Step 1: 写失败测试（5 个边界场景）**

在 `CompactTest.java` 的辅助方法前追加 5 个测试方法：

```java
	@Test
	public void should_print_warning_when_no_exec_files_are_provided()
			throws Exception {
		final File dest = new File(tmp.getRoot(), "output.exec");
		execute("compact", "--classfiles", getClassPath(), "--destfile",
				dest.getAbsolutePath());

		assertOk();
		assertContains("[WARN] No execution data files provided.", out);
		assertEquals(Collections.emptySet(), loadExecFile(dest));
	}

	@Test
	public void should_warn_when_no_classes_match() throws Exception {
		final File input = createExecFile("Stale", 0x12345678L);
		final File classDir = new File(tmp.getRoot(), "emptyClasses");
		classDir.mkdirs();
		final File dest = new File(tmp.getRoot(), "output.exec");

		execute("compact", "--classfiles", classDir.getAbsolutePath(),
				input.getAbsolutePath(), "--destfile",
				dest.getAbsolutePath());

		assertOk();
		assertContains(
				"[WARN] No execution data matches the given class files.",
				out);
		assertEquals(Collections.emptySet(), loadExecFile(dest));
	}

	@Test
	public void should_reject_destfile_equal_to_input() throws Exception {
		final File input = createExecFile("Stale", 0x12345678L);

		execute("compact", "--classfiles", getClassPath(),
				input.getAbsolutePath(), "--destfile",
				input.getAbsolutePath());

		assertFailure();
		assertContains(
				"[ERROR] The destfile must not be one of the input exec files.",
				err);
	}

	@Test
	public void should_scan_nested_directories() throws Exception {
		// 目标 class 放在嵌套目录 classes/a/b/c 下
		final File targetClass = new File(getClassPath(),
				"org/jacoco/cli/internal/CommandTestBase.class");
		final File nestedDir = new File(tmp.getRoot(), "classes/a/b/c");
		nestedDir.mkdirs();
		copy(targetClass, new File(nestedDir, "Command.class"));
		final long targetId = CRC64.classId(
				InputStreams.readFully(new FileInputStream(targetClass)));
		final File input = createExecFile("Command", targetId);
		final File dest = new File(tmp.getRoot(), "output.exec");

		execute("compact", "--classfiles",
				new File(tmp.getRoot(), "classes").getAbsolutePath(),
				input.getAbsolutePath(), "--destfile",
				dest.getAbsolutePath());

		assertOk();
		assertEquals(Collections.singleton("Command"), loadExecFile(dest));
	}

	@Test
	public void should_scan_jar_class_files() throws Exception {
		final File targetClass = new File(getClassPath(),
				"org/jacoco/cli/internal/CommandTestBase.class");
		final byte[] classBytes = InputStreams
				.readFully(new FileInputStream(targetClass));
		final File jar = new File(tmp.getRoot(), "classes.jar");
		final JarOutputStream jarOut = new JarOutputStream(
				new FileOutputStream(jar));
		jarOut.putNextEntry(
				new ZipEntry("org/jacoco/cli/internal/Command.class"));
		jarOut.write(classBytes);
		jarOut.closeEntry();
		jarOut.close();
		final long targetId = CRC64.classId(classBytes);
		final File input = createExecFile("Command", targetId);
		final File dest = new File(tmp.getRoot(), "output.exec");

		execute("compact", "--classfiles", jar.getAbsolutePath(),
				input.getAbsolutePath(), "--destfile",
				dest.getAbsolutePath());

		assertOk();
		assertEquals(Collections.singleton("Command"), loadExecFile(dest));
	}
```

在辅助方法区追加 `copy` 方法：

```java
	private void copy(File source, File target) throws IOException {
		final FileOutputStream out = new FileOutputStream(target);
		out.write(InputStreams.readFully(new FileInputStream(source)));
		out.close();
	}
```

import 区追加两行（`JarOutputStream` 与 `ZipEntry`）：

```java
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
```

- [ ] **Step 2: 运行测试，确认只有 destfile 防护用例失败**

运行: `./mvnw -pl org.jacoco.agent.rt,org.jacoco.cli.test -am package -Dtest=CompactTest -DfailIfNoTests=false`
预期: BUILD FAILURE，仅 `should_reject_destfile_equal_to_input` 失败（`execute` 目前会直接流式写入，截断输入并返回 0 → `assertFailure` 失败）；其余 4 个用例通过。

- [ ] **Step 3: 实现 destfile 同路径防护**

在 `Compact.java` 的 `execute` 方法第一行（`final File parent = destfile.getParentFile();` 之前）插入：

```java
		// 输出文件不能与输入文件相同：流式写入会截断正在读取的输入
		for (final File file : execfiles) {
			if (file.getCanonicalPath().equals(destfile.getCanonicalPath())) {
				err.println(
						"[ERROR] The destfile must not be one of the input exec files: "
								+ destfile.getAbsolutePath());
				return -1;
			}
		}
```

- [ ] **Step 4: 运行全部 CompactTest 验证通过**

运行: `./mvnw -pl org.jacoco.agent.rt,org.jacoco.cli.test -am package -Dtest=CompactTest -DfailIfNoTests=false`
预期: BUILD SUCCESS，`Tests run: 8, Failures: 0`（usage + 2 核心 + 5 边界）。

- [ ] **Step 5: 全量回归（含 MainTest / XmlDocumentationTest）**

运行: `./mvnw -pl org.jacoco.cli.test -am test`
预期: BUILD SUCCESS，`org.jacoco.cli.test` 全部测试通过（`MainTest` 的命令列表子串断言 `"dump|instrument|merge|report"` 不受影响；`XmlDocumentationTest` 自动生成含 compact 的文档 XML 并校验格式合法）。

- [ ] **Step 6: 提交**

```bash
git add org.jacoco.cli/src/org/jacoco/cli/internal/commands/Compact.java org.jacoco.cli.test/src/org/jacoco/cli/internal/commands/CompactTest.java
git commit -m "feat(cli): compact 命令增加 destfile 同路径防护及边界测试"
```

---

## 验收标准

- `./mvnw -pl org.jacoco.cli.test -am test` 全绿（含既有 MainTest/XmlDocumentationTest）。
- 手工验证（可选，构建 `jacococli.jar` 后）：

```bash
java -jar org.jacoco.cli/target/jacococli.jar compact \
  --classfiles <目标版本 class 目录> \
  <合并后的 exec> \
  --destfile <精简后的 exec>
```

预期：输出文件体积显著缩小；`[INFO] Compacted: kept X of Y classes (dropped Z).` 中 X ≈ 目标版本类数；后续用该输出生成报告的结果与精简前一致。
